package com.example.contract

import com.example.state.BondState
import net.corda.core.contracts.*
import net.corda.core.contracts.Requirements.using
import net.corda.core.identity.CordaX500Name
import net.corda.core.transactions.LedgerTransaction
import net.corda.finance.contracts.getCashBalance
import java.security.PublicKey

class BondContract : Contract {

    // Companion when use it can skip object name e.x. BondContact.Bond_CONTRACT_ID(It dont use obejct name to call)
    companion object {
        @JvmStatic
        val Bond_CONTRACT_ID = "com.example.contract.BondContract"
    }

    interface Commands : CommandData {
        class Issue : TypeOnlyCommandData(),Commands
        class Update : TypeOnlyCommandData(),Commands
        class Redeem : TypeOnlyCommandData(),Commands
    }

    override fun verify(tx: LedgerTransaction) {
        // Command from commands (issue,update,redeem)
        val command = tx.commands.requireSingleCommand<Commands>()

        // Set of signer in command data
        val setOfSigners = command.signers.toSet()

        when(command.value){
            is Commands.Issue -> verifyIssue(tx, setOfSigners)
            is Commands.Update -> verifyUpdate(tx, setOfSigners)
            is Commands.Redeem -> verifyEndBond(tx, setOfSigners)
            else -> throw IllegalArgumentException("Unrecognised command.")
        }
    }

    private fun verifyIssue(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {

        //--------------------------------
        // Verify input and output
        //--------------------------------
        "No inputs should be consumed when issuing an Bond." using (tx.inputs.isEmpty())
        "Only one output bond state should be created." using (tx.outputs.size == 1)
        // There can only be one output state and it must be a Bond state.
        // as unsafe check throw exception when It's not a bondstate type
        val bondOut = tx.outputStates.single() as BondState
        "The lender and the borrower cannot be the same entity." using (bondOut.lender != bondOut.owner)
        "The Financial and the borrower cannot be the same entity." using (bondOut.financial != bondOut.owner)

        //--------------------------------
        // Verify signer
        //--------------------------------
        val command = tx.commands.requireSingleCommand<Commands.Issue>()
        "All of the participants must be signers." using (command.signers.containsAll(bondOut.participants.map { it.owningKey }))

        //--------------------------------
        // Verify logic
        //--------------------------------
        // Assert stuff over the state.
        "A newly issued bond must have a positive target." using (bondOut.amount.quantity > 0)

    }

    private fun verifyUpdate(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {

        //--------------------------------
        // Verify input and output
        //--------------------------------
        "The can only one input state in Update bond." using (tx.inputStates.size == 1)
        "There must be two output states in Update bond." using (tx.outputStates.size == 1)
        // There can only be one input state and it must be a Bond state.
        val bondIn = tx.inputStates.single() as BondState
        // There can only be one output state and it must be a Bond state.
        val bondOut = tx.outputStates.single() as BondState

        //--------------------------------
        // Verify signer
        //--------------------------------
        val command = tx.commands.requireSingleCommand<Commands.Update>()
        "All of the participants (Output) must be signers." using (command.signers.containsAll(bondOut.participants.map { it.owningKey }))
        "All of the participants (Input) must be signers." using (command.signers.containsAll(bondIn.participants.map { it.owningKey }))

        //--------------------------------
        // Verify logic
        //--------------------------------
        "Update bond must have a positive target." using (bondOut.amount.quantity > 0)
        "Amount must less than amount in old bond" using(bondIn.amount.quantity > bondOut.amount.quantity)
    }

    private fun verifyEndBond(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {

        //--------------------------------
        // Verify input and output
        //--------------------------------
        "The can only one input state in End bond.." using (tx.inputs.size == 1)
        "No output should be consumed when Ending an Bond." using (tx.outputs.isEmpty())
        val bondIn = tx.inputStates.single() as BondState

        //--------------------------------
        // Verify signer
        //--------------------------------
        val command = tx.commands.requireSingleCommand<Commands.Redeem>()
        "All of the participants (Input) must be signers." using (command.signers.containsAll(bondIn.participants.map { it.owningKey }))

        //--------------------------------
        // Verify logic
        //--------------------------------
    }

}