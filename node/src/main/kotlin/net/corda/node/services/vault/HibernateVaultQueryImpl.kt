package net.corda.node.services.vault

import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.StateRef
import net.corda.core.contracts.TransactionState
import net.corda.core.crypto.SecureHash
import net.corda.core.node.services.Vault
import net.corda.core.node.services.VaultQueryService
import net.corda.core.node.services.vault.*
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.serialization.deserialize
import net.corda.core.serialization.storageKryo
import net.corda.core.utilities.loggerFor
import net.corda.node.services.database.HibernateConfiguration
import net.corda.node.services.vault.schemas.jpa.VaultSchemaV1
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.lang.Exception
import javax.persistence.EntityManager


class HibernateVaultQueryImpl(hibernateConfig: HibernateConfiguration) : SingletonSerializeAsToken(), VaultQueryService {

    companion object {
        val log = loggerFor<HibernateVaultQueryImpl>()
    }

    private val sessionFactory = hibernateConfig.sessionFactoryForSchema(VaultSchemaV1)
    private val criteriaBuilder = sessionFactory.criteriaBuilder

    @Throws(VaultQueryException::class)
    override fun <T : ContractState> _queryBy(criteria: QueryCriteria, paging: PageSpecification, sorting: Sort, contractType: Class<out ContractState>): Vault.Page<T> {

        log.info("Vault Query for contract type: $contractType, criteria: $criteria, pagination: $paging, sorting: $sorting")

        // set defaults: UNCONSUMED, ContractTypes
        val contractTypes = deriveContractTypes(contractType)
        val criteria =
                if (criteria is QueryCriteria.VaultQueryCriteria) {
                    val combinedContractStateTypes = criteria.contractStateTypes?.plus(contractTypes) ?: contractTypes
                    criteria.copy(contractStateTypes = combinedContractStateTypes)
                } else {
                    criteria.and(QueryCriteria.VaultQueryCriteria(status = Vault.StateStatus.UNCONSUMED, contractStateTypes = contractTypes))
                }

        val session = sessionFactory.withOptions().
                connection(TransactionManager.current().connection).
                openSession()

        session.use {
            val criteriaQuery = criteriaBuilder.createQuery(VaultSchemaV1.VaultStates::class.java)
            val queryRootVaultStates = criteriaQuery.from(VaultSchemaV1.VaultStates::class.java)

            val contractTypeMappings = resolveUniqueContractStateTypes(session)
            val criteriaParser = HibernateQueryCriteriaParser(contractTypeMappings, criteriaBuilder, criteriaQuery, queryRootVaultStates)

            try {
                // parse criteria and build where predicates
                criteriaParser.parse(criteria)

                // sorting
                sorting.columns.map {
                    when (it.direction) {
                        Sort.Direction.ASC ->
                            criteriaQuery.orderBy(criteriaBuilder.asc(queryRootVaultStates.get<String>(it.columnName)))
                        Sort.Direction.DESC ->
                            criteriaQuery.orderBy(criteriaBuilder.desc(queryRootVaultStates.get<String>(it.columnName)))
                    }
                }

                // prepare query for execution
                val query = session.createQuery(criteriaQuery)

                // pagination
                val countQuery = criteriaBuilder.createQuery(Long::class.java)
                countQuery.select(criteriaBuilder.count(countQuery.from(VaultSchemaV1.VaultStates::class.java)))
                val totalStates = session.createQuery(countQuery).singleResult.toInt()

                query.firstResult = paging.pageNumber * paging.pageSize
                query.maxResults = paging.pageSize

                // execution
                val results = query.resultList
                val statesAndRefs: MutableList<StateAndRef<*>> = mutableListOf()
                val statesMeta: MutableList<Vault.StateMetadata> = mutableListOf()

                results.asSequence()
                        .forEach { it ->
                            val stateRef = StateRef(SecureHash.parse(it.stateRef!!.txId!!), it.stateRef!!.index!!)
                            val state = it.contractState.deserialize<TransactionState<T>>(storageKryo())
                            statesMeta.add(Vault.StateMetadata(stateRef, it.contractStateClassName, it.recordedTime, it.consumedTime, it.stateStatus, it.notaryName, it.notaryKey, it.lockId, it.lockUpdateTime))
                            statesAndRefs.add(StateAndRef(state, stateRef))
                        }

                return Vault.Page(states = statesAndRefs, statesMetadata = statesMeta, pageable = paging, totalStatesAvailable = totalStates) as Vault.Page<T>

            } catch (e: Exception) {
                log.error(e.message)
                throw e.cause ?: e
            }
        }
    }

    override fun <T : ContractState> trackBy(criteria: QueryCriteria, paging: PageSpecification, sorting: Sort): Vault.PageAndUpdates<T> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    /**
     * Maintain a list of contract state interfaces to concrete types stored in the vault
     * for usage in generic queries of type queryBy<LinearState> or queryBy<FungibleState<*>>
     */
    fun resolveUniqueContractStateTypes(session: EntityManager) : MutableMap<String, MutableList<String>> {

            val criteria = criteriaBuilder.createQuery(String::class.java)
            val vaultStates = criteria.from(VaultSchemaV1.VaultStates::class.java)
            criteria.select(vaultStates.get("contractStateClassName")).distinct(true)
            val query = session.createQuery(criteria)
            val results = query.resultList
            val distinctTypes = results.map { it }

            val contractInterfaceToConcreteTypes = mutableMapOf<String, MutableList<String>>()
            distinctTypes.forEach { it ->
                val concreteType = Class.forName(it) as Class<ContractState>
                val contractInterfaces = deriveContractInterfaces(concreteType)
                contractInterfaces.map {
                    val contractInterface = contractInterfaceToConcreteTypes.getOrPut(it.name, { mutableListOf() })
                    contractInterface.add(concreteType.name)
                }
            }
            return contractInterfaceToConcreteTypes
    }

    private fun <T: ContractState> deriveContractInterfaces(clazz: Class<T>): Set<Class<T>> {
        val myInterfaces: MutableSet<Class<T>> = mutableSetOf()
        clazz.interfaces.forEach {
            if (!it.equals(ContractState::class.java)) {
                myInterfaces.add(it as Class<T>)
                myInterfaces.addAll(deriveContractInterfaces(it))
            }
        }
        return myInterfaces
    }
}