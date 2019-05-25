package com.example.flow

import co.paralleluniverse.fibers.Suspendable
import com.example.contract.BlacklistContract
import com.example.state.BlacklistState
import com.example.state.BondState
import net.corda.core.contracts.*
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker

object Blacklist_Update {
    @InitiatingFlow // initiate other flow
    @StartableByRPC // Start flow by RPC
    // Every flow is sub class of flow logic
    class Initiator(val blackRef: String) : FlowLogic<SignedTransaction>() {

        companion object {
            object GENERATING_TRANSACTION : ProgressTracker.Step("Generating transaction for Issue bond state.")
            object VERIFYING_TRANSACTION : ProgressTracker.Step("Verifying contract constraints.")
            object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with our private key.")
            object GATHERING_SIGS : ProgressTracker.Step("Gathering the counterparty's signature.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }

            object FINALISING_TRANSACTION : ProgressTracker.Step("Obtaining notary signature and recording transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(
                    GENERATING_TRANSACTION,
                    VERIFYING_TRANSACTION,
                    SIGNING_TRANSACTION,
                    GATHERING_SIGS,
                    FINALISING_TRANSACTION
            )
        }

        override val progressTracker = tracker()

        @Suspendable // run multiple flow in concurrently (need checkpointable and serialization to disk)
        override fun call(): SignedTransaction {
            // Use servicehub offer
            // Obtain a reference to the notary we want to use.
            val notary = serviceHub.networkMapCache.notaryIdentities[0]

            // Stage 1.
            progressTracker.currentStep = GENERATING_TRANSACTION
            val blackSecureHash = SecureHash.parse(blackRef)
            val stateRefBlack = StateRef(blackSecureHash,0)
            val blackState = serviceHub.loadState(stateRefBlack).data as BlacklistState
            val blackInputStateAndRef = serviceHub.toStateAndRef<BlacklistState>(stateRefBlack)

            val newBlackPoint = blackState.point + 1
            val blackOutputState = blackState.copy(point = newBlackPoint)
            val blackOutputStateAndContract = StateAndContract(blackOutputState, BlacklistContract.BLACK_CONTRACT_ID)
            val txCommand = Command(BlacklistContract.Commands.BlackUpdate(), blackState.participants.map { it.owningKey })
            val txBuilder = TransactionBuilder(notary).withItems(
                    blackInputStateAndRef,
                    blackOutputStateAndContract,
                    txCommand
            )

            // Stage 2.
            progressTracker.currentStep = VERIFYING_TRANSACTION
            // Verify that the transaction is valid. (verify by contract in com.example.contract.BondContract)
            txBuilder.verify(serviceHub)

            // Stage 3.
            progressTracker.currentStep = SIGNING_TRANSACTION
            // Sign the transaction. (Sign by myself before send it to participant)
            val partSignedTx = serviceHub.signInitialTransaction(txBuilder)

            // Stage 4.
            progressTracker.currentStep = GATHERING_SIGS
            val flowBlackParty = initiateFlow(blackState.backlist)
            val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, setOf(flowBlackParty), GATHERING_SIGS.childProgressTracker()))

            // Stage 5.
            progressTracker.currentStep = FINALISING_TRANSACTION
            // Notarise and record the transaction in both parties' vaults.
            // return signed transaction
            return subFlow(FinalityFlow(fullySignedTx, FINALISING_TRANSACTION.childProgressTracker()))
        }
    }

    @InitiatedBy(Initiator::class) //respond massage from another flow take class responding as a parameter
    class Responder(val otherPartyFlow: FlowSession) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {
            val signTransactionFlow = object : SignTransactionFlow(otherPartyFlow) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {

                }
            }

            return subFlow(signTransactionFlow)
        }
    }
}