.. highlight:: kotlin
.. raw:: html

   <script type="text/javascript" src="_static/jquery.js"></script>
   <script type="text/javascript" src="_static/codesets.js"></script>

API: Flows
==========

.. note:: Before reading this page, you should be familiar with the key concepts of :doc:`key-concepts-flows`.

FlowLogic
---------
A flow is implemented in code as one or more ``FlowLogic`` subclasses that communicate to handle a specific business
process. Each ``FlowLogic`` subclass must override ``FlowLogic.call()``, which describes the actions it will take as
part of the flow.

An example flow
^^^^^^^^^^^^^^^
As an example, let's design a flow for agreeing a basic ledger update between Alice and Bob. This flow will be
composed of two flow classes:

* An ``Initiator`` ``FlowLogic`` subclass, that will initiate the request to update the ledger
* A ``Responder`` ``FlowLogic`` subclass, that will respond to the request to update the ledger

Initiator
~~~~~~~~~
In our flow, the Initiator flow class will be doing the majority of the work. We therefore override ``Initiator.call``
to undertake the following steps:

*Part 1 - Build the transaction*

1. Choose a notary for the transaction
2. Create a transaction builder
3. Extract any input states from the vault and add them to the builder
4. Create any output states and add them to the builder
5. Add any commands, attachments and timestamps to the builder

*Part 2 - Sign the transaction*

6. Sign the transaction builder
7. Convert the builder to a signed transaction

*Part 3 - Verify the transaction*

8. Verify the transaction by running its contracts

*Part 4 - Gather the counterparty's signature*

9. Send the transaction to the counterparty
10. Wait to receive back the counterparty's signature
11. Add the counterparty's signature to the transaction
12. Verify the transaction's signatures

*Part 5 - Finalize the transaction*

13. Send the transaction to the notary
14. Wait to receive back the notarised transaction
15. Record the transaction locally
16. Store any relevant states in the vault
17. Send the transaction to the counterparty for recording

We can visualize the work performed by ``Initiator.call`` as follows:

.. image:: resources/flow-overview.png

In practice, parts 2 - 4 should be handled by invoking ``CollectSignaturesFlow`` as a subflow, and part 5 should be
handled by invoking ``FinalityFlow`` as a subflow (see subflow_ for details).

Responder
~~~~~~~~~
To respond to these actions, we override  ``Responder.call`` to take the following steps:

*Part 1 - Sign the transaction*

1. Receive the transaction from the counterparty
2. Verify the transaction's existing signatures
3. Verify the transaction by running its contracts
4. Generate a signature over the transaction
5. Send the signature back to the counterparty

*Part 2 - Record the transaction*

6. Receive the notarised transaction from the counterparty
7. Record the transaction locally
8. Store any relevant states in the vault

In practice, part 1 should be handled by invoking ``SignTransactionFlow`` as a subflow. Part 2 will be handled
automatically by our node when the counterparty invokes ``FinalityFlow``.

Flow automation
^^^^^^^^^^^^^^^
In practice, many of the actions in a flow can (and should) be automated using built-in flows called *subflows* (see
subflow_ for details).

In the example above:

* Parts 2-4 of the Initiator side should be automated by invoking ``CollectSignaturesFlow``
* Part 5 of the Initiator side should be automated by invoking ``FinalityFlow``
* Part 1 of the Responder side should be automated by invoking ``SignTransactionFlow``
* Part 2 of the Responder will be handled automatically when the counterparty invokes ``FinalityFlow``

ServiceHub
----------
Within ``FlowLogic.call()``, the flow developer has access to the node's ``ServiceHub`` that provides access to the
various services the node provides.

The key ``ServiceHub`` services are:

* ``ServiceHub.networkMapCache``
    * Provides info on other nodes on the network (e.g. notaries…)
* ``ServiceHub.vaultService``
    * Stores the node’s current and historic states
* ``ServiceHub.storageService``
    * Stores additional info such as transactions and attachments
* ``ServiceHub.keyManagementService``
    * Manages the node’s digital signing keys
* ``ServiceHub.myInfo``
    * Other information about the node
* ``ServiceHub.clock``
    * Provides access to the node’s internal time and date

Some common tasks performed using the ``ServiceHub`` are:

* Looking up your own identity or the identity of a counterparty using the ``networkMapCache``
* Identifying the providers of a given service (e.g. a notary service) using the ``networkMapCache``
* Retrieving states to use in a transaction using the ``vaultService``
* Retrieving attachments and past transactions to use in a transaction using the ``storageService``
* Creating a timestamp using the ``clock``
* Signing a transaction using the ``keyManagementService``

Common flow tasks
-----------------
There are a number of common tasks that you will need to perform within ``FlowLogic.call`` in order to agree ledger
updates. This section details the API for the most common tasks:

Communication between parties
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
``FlowLogic`` instances communicate using three functions:

* ``send(otherParty: Party, payload: Any)``
    * Sends the ``payload`` object to the ``otherParty``
* ``receive(receiveType: Class<R>, otherParty: Party)``
    * Receives an object of type ``receiveType`` from the ``otherParty``
* ``sendAndReceive(receiveType: Class<R>, otherParty: Party, payload: Any)``
    * Sends the ``payload`` object to the ``otherParty``, and receives an object of type ``receiveType`` back

Each ``FlowLogic`` subclass can be annotated to respond to messages from a given *counterparty* flow. When a node
first receives a message from a given ``FlowLogic.call()`` invocation, it responds as follows:

* The node checks whether they have a ``FlowLogic`` subclass that is registered to respond to the ``FlowLogic`` that
  is sending the message:

    a. If yes, the node starts an instance of this ``FlowLogic`` by invoking ``FlowLogic.call()``
    b. Otherwise, the node ignores the message

* The counterparty steps through their ``FlowLogic.call()`` method until they encounter a call to ``receive()``, at
  which point they process the message from the initiator

Upon calling ``receive()``/``sendAndReceive()``, the ``FlowLogic`` is suspended until it receives a response.

UntrustworthyData
~~~~~~~~~~~~~~~~~
``send()`` and ``sendAndReceive()`` return a payload wrapped in an ``UntrustworthyData`` instance. This is a
reminder that any data received off the wire is untrustworthy and must be verified.

We verify the ``UntrustworthyData`` and retrieve its payload by calling ``unwrap``:

.. container:: codeset

   .. sourcecode:: kotlin

        val partSignedTx = receive<SignedTransaction>(otherParty).unwrap { partSignedTx ->
                val wireTx = partSignedTx.verifySignatures(keyPair.public, notaryPubKey)
                wireTx.toLedgerTransaction(serviceHub).verify()
                partSignedTx
            }

   .. sourcecode:: java

        final SignedTransaction partSignedTx = receive(SignedTransaction.class, otherParty)
            .unwrap(tx -> {
                try {
                    final WireTransaction wireTx = tx.verifySignatures(keyPair.getPublic(), notaryPubKey);
                    wireTx.toLedgerTransaction(getServiceHub()).verify();
                } catch (SignatureException ex) {
                    throw new FlowException(tx.getId() + " failed signature checks", ex);
                }
                return tx;
            });

Retrieving information about other nodes
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
We use the network map to retrieve information about other nodes on the network:

.. container:: codeset

   .. sourcecode:: kotlin

        val networkMap = serviceHub.networkMapCache

        val allNodes = networkMap.partyNodes
        val allNotaryNodes = networkMap.notaryNodes
        val randomNotaryNode = networkMap.getAnyNotary()

        val alice = networkMap.getNodeByLegalName(X500Name("CN=Alice,O=Alice,L=London,C=UK"))
        val bob = networkMap.getNodeByLegalIdentityKey(bobsKey)

   .. sourcecode:: java

        final NetworkMapCache networkMap = getServiceHub().getNetworkMapCache();

        final List<NodeInfo> allNodes = networkMap.getPartyNodes();
        final List<NodeInfo> allNotaryNodes = networkMap.getNotaryNodes();
        final Party randomNotaryNode = networkMap.getAnyNotary(null);

        final NodeInfo alice = networkMap.getNodeByLegalName(new X500Name("CN=Alice,O=Alice,L=London,C=UK"));
        final NodeInfo bob = networkMap.getNodeByLegalIdentityKey(bobsKey);

Verifying a transaction
^^^^^^^^^^^^^^^^^^^^^^^
We verify a transaction as follows:

* Before verifying a transaction chain, we need to retrieve from the proposer(s) of the transaction any parts of the
  transaction chain that our node doesn't currently have in its local storage:

.. container:: codeset

   .. sourcecode:: kotlin

        subFlow(ResolveTransactionsFlow(transactionToVerify, partyWithTheFullChain))

   .. sourcecode:: java

        subFlow(new ResolveTransactionsFlow(transactionToVerify, partyWithTheFullChain));

* We then verify the transaction as follows:

.. container:: codeset

   .. sourcecode:: kotlin

        partSignedTx.toWireTransaction().toLedgerTransaction(serviceHub).verify()

   .. sourcecode:: java

        partSignedTx.toWireTransaction().toLedgerTransaction(getServiceHub()).verify();

* We will generally also want to conduct some custom validation of the transaction, beyond what is provided for in the
  contract:

.. container:: codeset

   .. sourcecode:: kotlin

        val ledgerTransaction = partSignedTx.tx.toLedgerTransaction(serviceHub)
        val inputStateAndRef = ledgerTransaction.inputs.single()
        val input = inputStateAndRef.state.data as MyState
        if (input.value > 1000000) {
            throw FlowException("Proposed input value too high!")
        }

   .. sourcecode:: java

        final LedgerTransaction ledgerTransaction = partSignedTx.getTx().toLedgerTransaction(getServiceHub());
        final StateAndRef inputStateAndRef = ledgerTransaction.getInputs().get(0);
        final MyState input = (MyState) inputStateAndRef.getState().getData();
        if (input.getValue() > 1000000) {
            throw new FlowException("Proposed input value too high!");
        }

Signing a transaction
^^^^^^^^^^^^^^^^^^^^^
We sign a transaction as follows:

* Initially, a ``SignedTransaction`` is generated from a ``TransactionBuilder`` using:

.. container:: codeset

   .. sourcecode:: kotlin

        val partSignedTx = serviceHub.signInitialTransaction(unsignedTx)

   .. sourcecode:: java

        final SignedTransaction partSignedTx = getServiceHub().signInitialTransaction(unsignedTx);

* Once a ``SignedTransaction`` has been created, we add additional signatures using:

.. container:: codeset

   .. sourcecode:: kotlin

        val fullySignedTx = serviceHub.addSignature(partSignedTx)

   .. sourcecode:: java

        SignedTransaction fullySignedTx = getServiceHub().addSignature(partSignedTx);

* We can also generate a signature without adding it to the transaction using:

.. container:: codeset

   .. sourcecode:: kotlin

        val signature = serviceHub.createSignature(partSignedTx)

   .. sourcecode:: java

        DigitalSignature.WithKey signature = getServiceHub().createSignature(partSignedTx);

.. _subflows:

Subflows
--------
Corda provides a number of built-in flows for handling common tasks. The most important are:

* ``CollectSignaturesFlow``, to collect a transaction's required signatures
* ``FinalityFlow``, to notarise and record a transaction
* ``ResolveTransactionsFlow``, to verify the chain of inputs to a transaction
* ``ContractUpgradeFlow``, to change a state's contract
* ``NotaryChangeFlow``, to change a state's notary

These flows are designed to be used as building blocks in your own flows. You do so by calling ``FlowLogic.subFlow()``
from within your flow's ``call()`` method. Here is an example from ``TwoPartyDealFlow.kt``:

.. container:: codeset

    .. literalinclude:: ../../core/src/main/kotlin/net/corda/flows/TwoPartyDealFlow.kt
        :language: kotlin
        :start-after: DOCSTART 1
        :end-before: DOCEND 1
        :dedent: 12

In this example, we are starting a ``CollectSignaturesFlow``, passing in a partially signed transaction, and
receiving back a fully-signed version of the same transaction.

FlowException
-------------
Suppose a node throws an exception while running a flow. Any counterparty flows waiting for a message from the node
(i.e. as part of a call to ``receive()`` or ``sendAndReceive()``) will be notified that the flow has unexpectedly
ended and will themselves end. However, the exception thrown will not be propagated back to the counterparties.

If you wish to notify any waiting counterparties of the cause of the exception, you can do so by throwing a
``FlowException``:

.. container:: codeset

    .. literalinclude:: ../../core/src/main/kotlin/net/corda/core/flows/FlowException.kt
        :language: kotlin
        :start-after: DOCSTART 1
        :end-before: DOCEND 1

The flow framework will automatically propagate the ``FlowException`` back to the waiting counterparties.

There are many scenarios in which throwing a ``FlowException`` would be appropriate:

* A transaction doesn't ``verify()``
* A transaction's signatures are invalid
* The transaction does not match the parameters of the deal as discussed
* You are reneging on a deal

Suspending flows
----------------
In order for nodes to be able to run multiple flows concurrently, and to allow flows to survive node upgrades and
restarts, flows need to be checkpointable and serializable to disk.

This is achieved by marking any function invoked from within ``FlowLogic.call()`` with an ``@Suspendable`` annotation.

We can see an example in ``CollectSignaturesFlow``:

.. container:: codeset

    .. literalinclude:: ../../core/src/main/kotlin/net/corda/flows/CollectSignaturesFlow.kt
        :language: kotlin
        :start-after: DOCSTART 1
        :end-before: DOCEND 1