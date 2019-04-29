package com.example.flow

import co.paralleluniverse.fibers.Suspendable
import com.example.contract.BondContract
import com.example.state.BondState
import net.corda.confidential.IdentitySyncFlow
import net.corda.core.contracts.Command
import net.corda.core.contracts.Requirements.using
import net.corda.core.contracts.StateAndContract
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker

object BondFlow_Redeem {
    @InitiatingFlow
    @StartableByRPC
    class Initiator(val bondref: UniqueIdentifier) : FlowLogic<SignedTransaction>() {

        companion object {
            object GENERATING_TRANSACTION : ProgressTracker.Step("Generating transaction based for redeem bond state.")
            object VERIFYING_TRANSACTION : ProgressTracker.Step("Verifying contract constraints. V3")
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

        @Suspendable
        override fun call(): SignedTransaction {
            val notary = serviceHub.networkMapCache.notaryIdentities[0]

            val queryCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(bondref))
            val bondInputStateAndRef = serviceHub.vaultService.queryBy<BondState>(queryCriteria).states.single()
            val bondState = bondInputStateAndRef.state.data

            progressTracker.currentStep = GENERATING_TRANSACTION
            val txCommand = Command(BondContract.Commands.Redeem(), listOf(bondState.owner.owningKey,bondState.lender.owningKey))
            val txBuilder = TransactionBuilder(notary).withItems(
                    bondInputStateAndRef,
                    txCommand
            )

            progressTracker.currentStep = VERIFYING_TRANSACTION
//            txBuilder.verify(serviceHub)

            progressTracker.currentStep = SIGNING_TRANSACTION
            val partSignedTx = serviceHub.signInitialTransaction(txBuilder)

            progressTracker.currentStep = GATHERING_SIGS
            val otherPartyFlow = initiateFlow(bondState.lender)
            subFlow(IdentitySyncFlow.Send(otherSide = otherPartyFlow, tx = partSignedTx.tx))
            val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, setOf(otherPartyFlow), GATHERING_SIGS.childProgressTracker()))


            progressTracker.currentStep = FINALISING_TRANSACTION
            return subFlow(FinalityFlow(fullySignedTx, FINALISING_TRANSACTION.childProgressTracker()))
        }
    }

    @InitiatedBy(Initiator::class)
    class Responder(val otherPartyFlow: FlowSession) : FlowLogic<SignedTransaction>() {

        @Suspendable
        override fun call(): SignedTransaction {
            subFlow(IdentitySyncFlow.Receive(otherSideSession = otherPartyFlow))
            val signTransactionFlow = object : SignTransactionFlow(otherPartyFlow) {
                override fun checkTransaction(stx: SignedTransaction) = Unit
            }

            return subFlow(signTransactionFlow)
        }
    }
}