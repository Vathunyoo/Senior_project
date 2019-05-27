package com.example.flow

import co.paralleluniverse.fibers.Suspendable
import com.example.contract.BondContract
import com.example.state.BlacklistState
import com.example.state.BondState
import net.corda.core.contracts.*
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.node.services.queryBy
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.finance.contracts.getCashBalance

object BondFlow_Redeem {
    @InitiatingFlow
    @StartableByRPC
    class Initiator(val bondref: String) : FlowLogic<SignedTransaction>() {

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
            val secureHashBond = SecureHash.parse(bondref)
            val stateRefBond = StateRef(secureHashBond,0)
            val bondState = serviceHub.loadState(stateRefBond).data as BondState
            val bondInputStateAndRef = serviceHub.toStateAndRef<BondState>(stateRefBond)

            progressTracker.currentStep = GENERATING_TRANSACTION
            val txCommand = Command(BondContract.Commands.Redeem(), listOf(bondState.owner.owningKey,bondState.lender.owningKey,bondState.escrow.owningKey))
            val txBuilder = TransactionBuilder(notary).withItems(
                    bondInputStateAndRef,
                    txCommand
            )

            progressTracker.currentStep = VERIFYING_TRANSACTION
//            txBuilder.verify(serviceHub)

            progressTracker.currentStep = SIGNING_TRANSACTION
            val partSignedTx = serviceHub.signInitialTransaction(txBuilder)

            progressTracker.currentStep = GATHERING_SIGS
            val ownerCashBalance = serviceHub.getCashBalance(bondState.amount.token)
            val flowLender = initiateFlow(bondState.lender)
            val flowEscrow = initiateFlow(bondState.escrow)
            val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, setOf(flowLender,flowEscrow), GATHERING_SIGS.childProgressTracker()))


            progressTracker.currentStep = FINALISING_TRANSACTION
            // Show data for owner
            logger.info("---------------------------------------------------------------------")
            logger.info("My info (owner) : " + serviceHub.myInfo.toString())
            logger.info("\n")
            logger.info("Cash balance (owner) : " + ownerCashBalance.toString() + " " + ownerCashBalance.token.toString())
            logger.info("\n")
            logger.info("Bond state output : " + bondState.toString())
            logger.info("\n")
            logger.info("Command for bond state (redeem) : " + txCommand.toString())
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

            val signTransactionFlow = object : SignTransactionFlow(otherPartyFlow) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    val input = stx.tx.inputs[0]
                    val bondIn = serviceHub.loadState(input).data as BondState
                    val x500NameEscrow = CordaX500Name.parse("O=Escrow,L=Paris,C=FR")
                    val escrow = serviceHub.identityService.wellKnownPartyFromX500Name(x500NameEscrow)
                    val queryVaultPage = serviceHub.vaultService.queryBy<BlacklistState>()
                    val listStateAndRef = queryVaultPage.states
                    if(serviceHub.myInfo.isLegalIdentity(bondIn.escrow)){
                        val escrowCashBalance = serviceHub.getCashBalance(bondIn.amount.token)
                        if(bondIn.status == "pending"){
                            var check = true
                            for(stateRef in listStateAndRef){
                                if(stateRef.state.data.blacklist == bondIn.owner){
                                    check = false
                                    subFlow(Blacklist_Update.Initiator(stateRef.ref.txhash.toString()))
                                }
                            }
                            if(check == true){
                                subFlow(Blacklist_Issue.Initiator(bondIn.owner,bondIn.escrow))
                            }
                        }
                        // Show data for Escrow
                        logger.info("---------------------------------------------------------------------")
                        logger.info("My info (escrow) : " + serviceHub.myInfo.toString())
                        logger.info("\n")
                        logger.info("Cash balance (escrow) : " + escrowCashBalance.toString() + " " + escrowCashBalance.token.toString())
                        logger.info("\n")
                        logger.info("Bond state input : " + bondIn.toString())
                        logger.info("\n")
                        logger.info("Command for bond state (redeem) : " + stx.tx.commands.toString())
                        logger.info("\n")
                        logger.info("Blacklist query vault.page" + queryVaultPage.toString())
                        logger.info("\n")
                        logger.info("Blacklist State and Ref list" + listStateAndRef.toString())
                        logger.info("---------------------------------------------------------------------")
                    }else if(serviceHub.myInfo.isLegalIdentity(bondIn.lender)){
                        // Show data for Lender
                        val lenderCashBalance = serviceHub.getCashBalance(bondIn.amount.token)
                        logger.info("---------------------------------------------------------------------")
                        logger.info("My info (lender) : " + serviceHub.myInfo.toString())
                        logger.info("\n")
                        logger.info("Cash balance (lender) : " + lenderCashBalance.toString() + " " + lenderCashBalance.token.toString())
                        logger.info("\n")
                        logger.info("Bond state input : " + bondIn.toString())
                        logger.info("\n")
                        logger.info("Command for bond state (redeem) : " + stx.tx.commands.toString())
                        logger.info("---------------------------------------------------------------------")
                    }else {

                    }
                    "Lender can't be a escrow" using (bondIn.lender != escrow)
                }
            }

            return subFlow(signTransactionFlow)
        }
    }
}