States
======

.. note:: Before reading this page, you should be familiar with the key concepts of :doc:`key-concepts-states`.

ContractState
-------------

In Corda, states are classes that implement ``ContractState``. The ``ContractState`` interface is defined as follows:

.. container:: codeset

    .. literalinclude:: ../../core/src/main/kotlin/net/corda/core/contracts/Structures.kt
        :language: kotlin
        :start-after: DOCSTART 1
        :end-before: DOCEND 1

Where:

* ``contract`` is the ``Contract`` class defining the constraints on transactions involving states of this type
* ``participants`` is a ``List`` of the ``PublicKey`` of each party involved in this state

ContractState sub-interfaces
----------------------------
There are two common sub-interfaces of ``ContractState``:

* ``LinearState``, which represents facts that evolve over time
* ``OwnableState``, which represents fungible assets

We can picture the hierarchy as follows:

.. image:: resources/state-hierarchy.png

Both interfaces provide a mechanism for the node's vault to ascertain whether the state is relevant to it (and
therefore worth tracking) or not.

LinearState
^^^^^^^^^^^
``LinearState`` models facts that evolve over time. Remember that in Corda, states are immutable and can't be
updated directly. Instead, we represent an evolving fact as a sequence of states where every state is a
``LinearState`` that shares the same ``linearId``.

The ``LinearState`` interface is defined as follows:

.. container:: codeset

    .. literalinclude:: ../../core/src/main/kotlin/net/corda/core/contracts/Structures.kt
        :language: kotlin
        :start-after: DOCSTART 2
        :end-before: DOCEND 2

Where:

* ``linearId`` is a ``UniqueIdentifier`` that:

  * Allows the successive versions of the fact to be linked over time
  * Provides an external identifier for referencing the state in external systems

* ``isRelevant(ourKeys: Set<PublicKey>)`` checks whether, given a set of keys we hold, this state is relevant and
  should be tracked by our vault

The vault tracks the head (i.e. the most recent version) of each ``LinearState`` chain (i.e. each sequence of
states all sharing a ``linearId``). To create a transaction updating a ``LinearState``, we retrieve the state from the
vault using its ``linearId``.

OwnableState
^^^^^^^^^^^^
``OwnableState`` models fungible assets - assets for which it's the quantity held that is important, rather than
the identity of the individual units.

The ``OwnableState`` interface is defined as follows:

.. container:: codeset

    .. literalinclude:: ../../core/src/main/kotlin/net/corda/core/contracts/Structures.kt
        :language: kotlin
        :start-after: DOCSTART 3
        :end-before: DOCEND 3

Where:

* ``owner`` is the ``PublicKey`` of the asset's owner

  * By default, the node's vault will track any ``OwnableState`` of which it is the owner

* ``withNewOwner(newOwner: PublicKey)`` creates an identical copy of the state, only with a new owner

Other interfaces
^^^^^^^^^^^^^^^^
``ContractState`` has several more sub-interfaces that can be implemented:

* ``QueryableState``, which allows the state to be queried in the node's database using SQL (see
  :doc:`event-scheduling`)
* ``SchedulableState``, which allows us to schedule future actions for the state (e.g. a coupon on a bond) (see
  :doc:`persistence`)

User-defined fields
-------------------
Beyond implementing ``LinearState`` or ``OwnableState``, the definition of the state is up to the CorDapp developer.
You can define any additional class fields and methods you see fit.

For example, here is a relatively complex state definition, for a state representing cash:

.. container:: codeset

    .. literalinclude:: ../../finance/src/main/kotlin/net/corda/contracts/asset/Cash.kt
        :language: kotlin
        :start-after: DOCSTART 1
        :end-before: DOCEND 1

TransactionState
----------------
When a ``ContractState`` is added to a ``TransactionBuilder``, it is wrapped in a ``TransactionState``:

.. container:: codeset

    .. literalinclude:: ../../core/src/main/kotlin/net/corda/core/contracts/Structures.kt
        :language: kotlin
        :start-after: DOCSTART 4
        :end-before: DOCEND 4

Where:

* ``data`` is the state to be stored on-ledger
* ``notary`` is the notary service for this state
* ``encumbrance`` points to another state that must also appear as an input to any transaction consuming this
  state