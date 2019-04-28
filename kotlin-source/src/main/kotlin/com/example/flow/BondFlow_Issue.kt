package com.example.flow

import co.paralleluniverse.fibers.Suspendable
import com.example.contract.BondContract
import com.example.contract.IOUContract
import com.example.state.BondState
import com.example.state.IOUState
import net.corda.core.contracts.Command
import net.corda.core.contracts.Requirements.using
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import java.time.Instant

object BondFlow_Issue {
    @InitiatingFlow // initiate other flow
    @StartableByRPC // Start flow by RPC
    // Every flow is sub class of flow logic
    class Initiator(val amount: Int,
                    val lender: Party) : FlowLogic<SignedTransaction>() {

        companion object {
            object GENERATING_TRANSACTION : ProgressTracker.Step("Generating transaction based on new IOU.")
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
            // Generate an unsigned transaction.
            val bondState = BondState(amount, serviceHub.myInfo.legalIdentities.first(), lender)
            // map (iterate every member in array) output on map is array of public key
            val txCommand = Command(BondContract.Commands.Issue(), bondState.participants.map { it.owningKey })
            // have input state or timewindow (in transaction builder must have notary)
            val txBuilder = TransactionBuilder(notary)
                    .addOutputState(bondState, BondContract.Bond_CONTRACT_ID)
                    .addCommand(txCommand)

            // Stage 2.
            progressTracker.currentStep = VERIFYING_TRANSACTION
            // Verify that the transaction is valid.
            txBuilder.verify(serviceHub)

            // Stage 3.
            progressTracker.currentStep = SIGNING_TRANSACTION
            // Sign the transaction.
            val partSignedTx = serviceHub.signInitialTransaction(txBuilder)

            // Stage 4.
            progressTracker.currentStep = GATHERING_SIGS
            // Send the state to the counterparty, and receive it back with their signature.
            // Create flow session to create communicate session to initiator and acceptor(responder)
            /*
            CollectSignaturesflow
            @param partiallySignedTx - Transaction to collect the remaining signatures for
            @param sessionsToCollectFrom - A session for every party we need to collect a signature from. Must be an exact match.
            @param myOptionalKeys - set of keys in the transaction which are owned by this node. This includes keys used on commands,
            not just in the states. If null, the default well known identity of the node is used.
             */
            val otherPartyFlow = initiateFlow(lender)
            val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, setOf(otherPartyFlow), GATHERING_SIGS.childProgressTracker()))

            // Stage 5.
            progressTracker.currentStep = FINALISING_TRANSACTION
            // Notarise and record the transaction in both parties' vaults.
            // return signed transaction
            return subFlow(FinalityFlow(fullySignedTx, FINALISING_TRANSACTION.childProgressTracker()))
        }
    }

    @InitiatedBy(Initiator::class) //respond massage from another flow take class responding as a parameter
    class Acceptor(val otherPartyFlow: FlowSession) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {
            val signTransactionFlow = object : SignTransactionFlow(otherPartyFlow) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    val output = stx.tx.outputs.single().data
//                    "This must be an IOU transaction." using (output is IOUState)
                    val iou = output as BondState
                    "I won't accept IOUs with a value over 100." using (iou.amount <= 100)
                }
            }

            return subFlow(signTransactionFlow)
        }
    }
}