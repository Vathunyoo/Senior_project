package com.example.flow

import co.paralleluniverse.fibers.Suspendable
import com.example.contract.BondContract
import com.example.state.BondState
import net.corda.core.contracts.*
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
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
                    val bondref: String) : FlowLogic<SignedTransaction>() {

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
            // Query by criteria
//            val queryCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(bondref))
//            val bondInputStateAndRef = serviceHub.vaultService.queryBy<BondState>(queryCriteria).states.single()
//            val bondState = bondInputStateAndRef.state.data

            val secureHashBond = SecureHash.parse(bondref)
            val stateRefBond = StateRef(secureHashBond,0)
            val bondState = serviceHub.loadState(stateRefBond).data as BondState
            val bondInputStateAndRef = serviceHub.toStateAndRef<BondState>(stateRefBond)

            progressTracker.currentStep = GENERATING_TRANSACTION
            val newQuatity = bondState.amount.quantity - amount.quantity
            val newAmount = Amount(newQuatity,amount.token)
            val bondOutputState = bondState.copy(amount = newAmount)
            val bondOutputStateAndContract = StateAndContract(bondOutputState, BondContract.Bond_CONTRACT_ID)
            val txCommand = Command(BondContract.Commands.Update(), listOf(bondState.owner.owningKey,bondState.lender.owningKey,bondState.escrow.owningKey))

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
            val flowEscrow = initiateFlow(bondOutputState.escrow)
            flowEscrow.send(amount.quantity)
            val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, setOf(flowLender,flowEscrow), GATHERING_SIGS.childProgressTracker()))
            subFlow(CashPaymentFlow(amount,bondOutputState.escrow))

            progressTracker.currentStep = FINALISING_TRANSACTION

            // Show data for owner
            logger.info("---------------------------------------------------------------------")
            logger.info("My info (owner) : " + serviceHub.myInfo.toString())
            logger.info("\n")
            logger.info("Cash balance (owner) : " + ownerCashBalance.toString() + " " + ownerCashBalance.token.toString())
            logger.info("\n")
            logger.info("Bond state output : " + bondState.toString())
            logger.info("\n")
            logger.info("Command for bond state (update) : " + txCommand.toString())
            logger.info("\n")
            logger.info("Flow session for lender : " + flowLender.toString())
            logger.info("\n")
            logger.info("Flow session for escrow : " + flowEscrow.toString())
            logger.info("\n")
            logger.info("Fully signed transaction : " + fullySignedTx.toString())
            logger.info("---------------------------------------------------------------------")

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
                    val input = stx.tx.inputs[0]
                    val bondIn = serviceHub.loadState(input).data as BondState
                    val output = stx.tx.outputs.single().data
                    val bondOut = output as BondState
                    val x500NameEscrow = CordaX500Name.parse("O=Escrow,L=Paris,C=FR")
                    val escrow = serviceHub.identityService.wellKnownPartyFromX500Name(x500NameEscrow)

                    if(serviceHub.myInfo.isLegalIdentity(bondOut.escrow)){
                        val paidAmount = Amount(amount,bondOut.amount.token)
                        val escrowCashBalance = serviceHub.getCashBalance(bondOut.amount.token)
                        // Show data for Escrow
                        logger.info("---------------------------------------------------------------------")
                        logger.info("My info (escrow) : " + serviceHub.myInfo.toString())
                        logger.info("\n")
                        logger.info("Cash balance (escrow) : " + escrowCashBalance.toString() + " " + escrowCashBalance.token.toString())
                        logger.info("\n")
                        logger.info("Bond state input : " + bondIn.toString())
                        logger.info("\n")
                        logger.info("Bond state output : " + bondOut.toString())
                        logger.info("\n")
                        logger.info("Command for bond state (update) : " + stx.tx.commands.toString())
                        logger.info("---------------------------------------------------------------------")
                        subFlow(CashPaymentFlow(paidAmount,bondOut.lender))
                    }else if(serviceHub.myInfo.isLegalIdentity(bondOut.lender)){
                        // Show data for Lender
                        val lenderCashBalance = serviceHub.getCashBalance(bondOut.amount.token)
                        logger.info("---------------------------------------------------------------------")
                        logger.info("My info (lender) : " + serviceHub.myInfo.toString())
                        logger.info("\n")
                        logger.info("Cash balance (lender) : " + lenderCashBalance.toString() + " " + lenderCashBalance.token.toString())
                        logger.info("\n")
                        logger.info("Bond state input : " + bondIn.toString())
                        logger.info("\n")
                        logger.info("Bond state output : " + bondOut.toString())
                        logger.info("\n")
                        logger.info("Command for bond state (update) : " + stx.tx.commands.toString())
                        logger.info("---------------------------------------------------------------------")
                    }else {

                    }
                    "Borrower can't be a escrow" using (bondOut.owner != escrow)
                    "Lender can't be a escrow" using (bondOut.lender != escrow)
                }
            }
            return subFlow(signTransactionFlow)
        }
    }
}