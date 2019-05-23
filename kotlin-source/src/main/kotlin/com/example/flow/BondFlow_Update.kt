package com.example.flow

import co.paralleluniverse.fibers.Suspendable
import com.example.contract.BondContract
import com.example.state.BondState
import net.corda.confidential.IdentitySyncFlow
import net.corda.core.contracts.*
import net.corda.core.contracts.Requirements.using
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap
import net.corda.finance.contracts.getCashBalance
import net.corda.finance.flows.CashPaymentFlow
import java.util.*

object BondFlow_Update {
    @InitiatingFlow
    @StartableByRPC
    class Initiator(val amount: Amount<Currency>,
                    val bondref: UniqueIdentifier) : FlowLogic<SignedTransaction>() {

        companion object {
            object GENERATING_TRANSACTION : ProgressTracker.Step("Generating transaction for Update bond state.")
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

        @Suspendable
        override fun call(): SignedTransaction {
            val notary = serviceHub.networkMapCache.notaryIdentities[0]

            val queryCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(bondref))
            val bondInputStateAndRef = serviceHub.vaultService.queryBy<BondState>(queryCriteria).states.single()
            val bondState = bondInputStateAndRef.state.data

            progressTracker.currentStep = GENERATING_TRANSACTION
            val newQuatity = bondState.amount.quantity - amount.quantity
            val newAmount = Amount(newQuatity,amount.token)
            val bondOutputState = bondState.copy(amount = newAmount)
            val bondOutputStateAndContract = StateAndContract(bondOutputState, BondContract.Bond_CONTRACT_ID)
            val txCommand = Command(BondContract.Commands.Update(), listOf(bondState.owner.owningKey,bondState.lender.owningKey,bondState.financial.owningKey))

            val txBuilder = TransactionBuilder(notary).withItems(
                    bondOutputStateAndContract,
                    bondInputStateAndRef,
                    txCommand
            )

            progressTracker.currentStep = VERIFYING_TRANSACTION
            txBuilder.verify(serviceHub)

            progressTracker.currentStep = SIGNING_TRANSACTION
            val partSignedTx = serviceHub.signInitialTransaction(txBuilder)

            progressTracker.currentStep = GATHERING_SIGS
            val ownerCashBalance = serviceHub.getCashBalance(bondState.amount.token)
            val flowLender = initiateFlow(bondOutputState.lender)
            flowLender.send(amount.quantity)
            val flowFinancial = initiateFlow(bondOutputState.financial)
            flowFinancial.send(amount.quantity)
            val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, setOf(flowLender,flowFinancial), GATHERING_SIGS.childProgressTracker()))
            subFlow(CashPaymentFlow(amount,bondOutputState.financial))

            progressTracker.currentStep = FINALISING_TRANSACTION
            return subFlow(FinalityFlow(fullySignedTx, FINALISING_TRANSACTION.childProgressTracker()))
        }
    }

    @InitiatedBy(Initiator::class)
    class Responder(val otherPartyFlow: FlowSession) : FlowLogic<SignedTransaction>() {

        @Suspendable
        override fun call(): SignedTransaction {
            val amount = otherPartyFlow.receive<Long>().unwrap { it }
            val signTransactionFlow = object : SignTransactionFlow(otherPartyFlow) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    val output = stx.tx.outputs.single().data
                    val bondOut = output as BondState
                    val x500NameFinancial = CordaX500Name.parse("O=Financial,L=Paris,C=FR")
                    val financial = serviceHub.identityService.wellKnownPartyFromX500Name(x500NameFinancial)

                    if(serviceHub.myInfo.isLegalIdentity(bondOut.financial)){
                        val paidAmount = Amount(amount,bondOut.amount.token)
                        subFlow(CashPaymentFlow(paidAmount,bondOut.lender))
                    }else if(serviceHub.myInfo.isLegalIdentity(bondOut.lender)){

                    }else {

                    }
                    "Borrower can't be a financial" using (bondOut.owner != financial)
                    "Lender can't be a financial" using (bondOut.lender != financial)
                }
            }
            return subFlow(signTransactionFlow)
        }
    }
}