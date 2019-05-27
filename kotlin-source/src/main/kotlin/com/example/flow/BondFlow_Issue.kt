package com.example.flow

import co.paralleluniverse.fibers.Suspendable
import com.example.contract.BondContract
import com.example.state.BlacklistState
import com.example.state.BondState
import jdk.nashorn.internal.runtime.JSType.toLong
import net.corda.core.contracts.Amount
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap
import net.corda.finance.contracts.getCashBalance
import net.corda.finance.flows.CashPaymentFlow
import java.time.Instant
import java.util.*

object BondFlow_Issue {
    @InitiatingFlow // initiate other flow
    @StartableByRPC // Start flow by RPC
    // Every flow is sub class of flow logic
    class Initiator(val amount: Amount<Currency>,
                    val lender: Party,
                    val escrow: Party,
                    val interest: Double,
                    val period: Int,
                    val duedate : Instant) : FlowLogic<SignedTransaction>() {

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
            // Add interest
            val amountInterest = (amount.quantity * interest)/100
            val newAmountQuatity = toLong(amount.quantity + amountInterest)
            val newAmount = Amount(newAmountQuatity,amount.token)
            // Generate an unsigned transaction.
            val bondState = BondState(newAmount, serviceHub.myInfo.legalIdentities.first(), lender, escrow,interest,period,"pending",duedate)
            val bondOutputStateAndContract = StateAndContract(bondState, BondContract.Bond_CONTRACT_ID)
            // map (iterate every member in array) output on map is array of public key
            val txCommand = Command(BondContract.Commands.Issue(), bondState.participants.map { it.owningKey })
            // in transaction builder must have notary
            val txBuilder = TransactionBuilder(notary).withItems(
                    bondOutputStateAndContract,
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
            // Send the state to the counterparty, and receive it back with their signature.
            // Create flow session to create communicate session to initiator and acceptor(responder)
            /*
            CollectSignaturesflow
            @param partiallySignedTx - Transaction to collect the remaining signatures for
            @param sessionsToCollectFrom - A session for every party we need to collect a signature from. Must be an exact match.
            @param myOptionalKeys - set of keys in the transaction which are owned by this node. This includes keys used on commands,
            not just in the states. If null, the default well known identity of the node is used.
             */
            val ownerCashBalance = serviceHub.getCashBalance(bondState.amount.token)
            val flowLender = initiateFlow(lender)
            flowLender.send(serviceHub.myInfo.legalIdentities.first())
            flowLender.send(ownerCashBalance)
            val flowEscrow = initiateFlow(escrow)
            flowEscrow.send(serviceHub.myInfo.legalIdentities.first())
            flowEscrow.send(ownerCashBalance)
            val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, setOf(flowLender,flowEscrow), GATHERING_SIGS.childProgressTracker()))

            // Stage 5.
            progressTracker.currentStep = FINALISING_TRANSACTION
            // Notarise and record the transaction in both parties' vaults.

            // Show data for owner
            logger.info("---------------------------------------------------------------------")
            logger.info("My info (owner) : " + serviceHub.myInfo.toString())
            logger.info("\n")
            logger.info("Cash balance (owner) : " + ownerCashBalance.toString() + " " + ownerCashBalance.token.toString())
            logger.info("\n")
            logger.info("Bond state output : " + bondState.toString())
            logger.info("\n")
            logger.info("Command for bond state (issue) : " + txCommand.toString())
            logger.info("\n")
            logger.info("Flow session for lender : " + flowLender.toString())
            logger.info("\n")
            logger.info("Flow session for escrow : " + flowEscrow.toString())
            logger.info("\n")
            logger.info("Fully signed transaction : " + fullySignedTx.toString())
            logger.info("---------------------------------------------------------------------")

            // return signed transaction
            return subFlow(FinalityFlow(fullySignedTx, FINALISING_TRANSACTION.childProgressTracker()))
        }
    }

    @InitiatedBy(Initiator::class) //respond massage from another flow take class responding as a parameter
    class Responder(val otherPartyFlow: FlowSession) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {
            val owner = otherPartyFlow.receive<Party>().unwrap { it }
            val amountOwner = otherPartyFlow.receive<Amount<Currency>>().unwrap { it }
            val signTransactionFlow = object : SignTransactionFlow(otherPartyFlow) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    val output = stx.tx.outputs.single().data
                    val bondOut = output as BondState
                    val x500NameEscrow = CordaX500Name.parse("O=Escrow,L=Paris,C=FR")
                    val escrow = serviceHub.identityService.wellKnownPartyFromX500Name(x500NameEscrow)
                    val x500NamePartyB = CordaX500Name.parse("O=PartyB,L=New York,C=US")
                    val partyB = serviceHub.identityService.wellKnownPartyFromX500Name(x500NamePartyB)
                    val queryVaultPage = serviceHub.vaultService.queryBy<BlacklistState>()
                    val listStateAndRef = queryVaultPage.states
                    "Escrow in bond state don't true" using (bondOut.escrow == escrow)
                    if(serviceHub.myInfo.isLegalIdentity(bondOut.escrow)){
                        "Borrower must have cash more than zero" using (amountOwner.quantity > 0)
                        val escrowCashBalance = serviceHub.getCashBalance(bondOut.amount.token)
//                        "Your node is in blacklist" using (owner != partyB)
                        for(stateRef in listStateAndRef){
                            if(stateRef.state.data.blacklist == bondOut.owner){
                                if(stateRef.state.data.point > 3){
                                    subFlow(CashPaymentFlow(bondOut.amount,bondOut.lender))
                                    "You are in blacklist (point more than 3)" using (stateRef.state.data.blacklist != bondOut.owner)
                                }
                            }
                        }
                        // Show data for Escrow
                        logger.info("---------------------------------------------------------------------")
                        logger.info("My info (escrow) : " + serviceHub.myInfo.toString())
                        logger.info("\n")
                        logger.info("Cash balance (escrow) : " + escrowCashBalance.toString() + " " + escrowCashBalance.token.toString())
                        logger.info("\n")
                        logger.info("Bond state output : " + bondOut.toString())
                        logger.info("\n")
                        logger.info("Command for bond state (issue) : " + stx.tx.commands.toString())
                        logger.info("\n")
                        logger.info("Blacklist query vault.page" + queryVaultPage.toString())
                        logger.info("\n")
                        logger.info("Blacklist State and Ref list" + listStateAndRef.toString())
                        logger.info("---------------------------------------------------------------------")

                        subFlow(CashPaymentFlow(bondOut.amount,bondOut.owner))
                    }else if(serviceHub.myInfo.isLegalIdentity(bondOut.lender)){
                        val lenderCashBalance = serviceHub.getCashBalance(bondOut.amount.token)
                        val limitedCash = lenderCashBalance.quantity / 5
                        "More than maximum limited cash in lender" using (bondOut.amount.quantity < limitedCash)
                        // Show data for Lender
                        logger.info("---------------------------------------------------------------------")
                        logger.info("My info (lender) : " + serviceHub.myInfo.toString())
                        logger.info("\n")
                        logger.info("Cash balance (lender) : " + lenderCashBalance.toString() + " " + lenderCashBalance.token.toString())
                        logger.info("\n")
                        logger.info("Bond state output : " + bondOut.toString())
                        logger.info("\n")
                        logger.info("Command for bond state (issue) : " + stx.tx.commands.toString())
                        logger.info("\n")
                        logger.info("Blacklist query vault.page" + queryVaultPage.toString())
                        logger.info("\n")
                        logger.info("Blacklist State and Ref list" + listStateAndRef.toString())
                        logger.info("---------------------------------------------------------------------")
                        subFlow(CashPaymentFlow(bondOut.amount,bondOut.escrow))
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