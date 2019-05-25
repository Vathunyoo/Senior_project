package com.example.contract

import com.example.state.BlacklistState
import com.example.state.BondState
import net.corda.core.contracts.*
import net.corda.core.contracts.Requirements.using
import net.corda.core.transactions.LedgerTransaction
import java.security.PublicKey

class BlacklistContract : Contract {

    // Companion when use it can skip object name e.x. BondContact.Bond_CONTRACT_ID(It dont use obejct name to call)
    companion object {
        @JvmStatic
        val BLACK_CONTRACT_ID = "com.example.contract.BlacklistContract"
    }

    interface Commands : CommandData {
        class BlackIssue : TypeOnlyCommandData(),Commands
        class BlackUpdate : TypeOnlyCommandData(),Commands
        class BlackEnd : TypeOnlyCommandData(),Commands
    }

    override fun verify(tx: LedgerTransaction) {
        // Command from commands (issue,update,redeem)
        val command = tx.commands.requireSingleCommand<Commands>()

        // Set of signer in command data
        val setOfSigners = command.signers.toSet()

        when(command.value){
            is Commands.BlackIssue -> verifyBlacklistIssue(tx, setOfSigners)
            is Commands.BlackUpdate -> verifyBlacklistUpdate(tx, setOfSigners)
            is Commands.BlackEnd-> verifyBlacklistEnd(tx, setOfSigners)
            else -> throw IllegalArgumentException("Unrecognised command.")
        }
    }

    private fun verifyBlacklistIssue(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {

        //--------------------------------
        // Verify input and output
        //--------------------------------
        "No inputs should be consumed when issuing an Blacklist." using (tx.inputs.isEmpty())
        "Only one output bond state should be created." using (tx.outputs.size == 1)
        val blackOut = tx.outputStates.single() as BlacklistState

        //--------------------------------
        // Verify signer
        //--------------------------------
        val command = tx.commands.requireSingleCommand<Commands.BlackIssue>()
        "All of the participants (Output) must be signers." using (command.signers.containsAll(blackOut.participants.map { it.owningKey }))

        //--------------------------------
        // Verify logic
        //--------------------------------
    }

    private fun verifyBlacklistUpdate(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {

        //--------------------------------
        // Verify input and output
        //--------------------------------
        "The can only one input state in Update black list." using (tx.inputStates.size == 1)
        "There must be two output states in Update blacklist." using (tx.outputStates.size == 1)
        val blackIn = tx.inputStates.single() as BlacklistState
        val blackOut = tx.outputStates.single() as BlacklistState

        //--------------------------------
        // Verify signer
        //--------------------------------
        val command = tx.commands.requireSingleCommand<Commands.BlackUpdate>()
        "All of the participants (Input) must be signers." using (command.signers.containsAll(blackIn.participants.map { it.owningKey }))
        "All of the participants (Output) must be signers." using (command.signers.containsAll(blackOut.participants.map { it.owningKey }))

        //--------------------------------
        // Verify logic
        //--------------------------------
    }

    private fun verifyBlacklistEnd(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {

        //--------------------------------
        // Verify input and output
        //--------------------------------
        "The can only one input state in End bond.." using (tx.inputs.size == 1)
        "No output should be consumed when Ending an Bond." using (tx.outputs.isEmpty())
        val blackIn = tx.inputStates.single() as BlacklistState

        //--------------------------------
        // Verify signer
        //--------------------------------
        val command = tx.commands.requireSingleCommand<Commands.BlackEnd>()
        "All of the participants (Input) must be signers." using (command.signers.containsAll(blackIn.participants.map { it.owningKey }))

        //--------------------------------
        // Verify logic
        //--------------------------------
    }

}