package com.example.flow

import com.example.state.BondState
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.LinearState
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.utilities.getOrThrow
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.StartedMockNode
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

class BondFlowTests {
    lateinit var network: MockNetwork
    lateinit var a: StartedMockNode
    lateinit var b: StartedMockNode
    lateinit var c: StartedMockNode

    // ---------------------------------------
    // Setting for check all component testing (Example Flow)
    // ---------------------------------------
//    @Before
//    fun setup() {
//        network = MockNetwork(listOf("com.example.contract", "com.example.schema"))
//        a = network.createPartyNode(CordaX500Name("Borrower", "Bangkae", "GB"))
//        b = network.createPartyNode()
//        c = network.createPartyNode(CordaX500Name("Financial", "Nana", "TH"))
//
//        // For real nodes this happens automatically, but we have to manually register the flow for tests.
//        // registerInitiatedFlow -> initiating responder flow (need for test only)
//        listOf(a, b, c).forEach { it.registerInitiatedFlow(ExampleFlow.Acceptor::class.java) }
//        network.runNetwork()
//    }

    // ---------------------------------------
    // Setting for check Bond Flow)
    // ---------------------------------------
    @Before
    fun setup() {
        network = MockNetwork(listOf("com.example.contract", "com.example.schema"))
        a = network.createPartyNode()
        b = network.createPartyNode()
        c = network.createPartyNode()
        // For real nodes this happens automatically, but we have to manually register the flow for tests.
        listOf(a, b, c).forEach { it.registerInitiatedFlow(BondFlow_Issue.Acceptor::class.java) }
        network.runNetwork()
    }
    @After
    fun tearDown() {
        network.stopNodes()
    }

//    @Test
//    fun `Check all component flow`() {
//
//        println("// ---------------------------------------")
//        println("// Initial flow")
//        println("// ---------------------------------------")
//        val flow = ExampleFlow.Initiator(1, b.info.singleIdentity())
//        // Start flow (return future -> future of output of running flow)
//        val future = a.startFlow(flow)
//        // Running network
//        network.runNetwork()
//        // Data Identity with node
//        println("// Node info")
//        println(a.info)
//        println("// Node identity (Party)")
//        println(a.info.singleIdentity())
//        println("// Owning key(Public key)")
//        println(a.info.singleIdentity().owningKey)
//        println("// Future output")
//        println(future.get())
//
////        println("// ---------------------------------------")
////        println("// Check exception")
////        println("// ---------------------------------------")
////        // assertFailsWith -> specific exception (have exception it pass , dont have it fail)
////        val except = assertFailsWith<TransactionVerificationException> { future.getOrThrow() }
////        println("Exception message")
////        println(except.message)
////        println("Exception cause")
////        println(except.cause)
//
//        println("// ---------------------------------------")
//        println("// Signed transaction")
//        println("// ---------------------------------------")
//        // Get signtransaction or throw exception when transaction it not pass a contract
//        val signedTx = future.getOrThrow()
//        // Verifies the signatures on this transaction and throws if any are missing which aren't passed as parameters
//        // verifySignaturesExcept -> check parameter that have in requiredSigningKeys
//        signedTx.verifySignaturesExcept(a.info.singleIdentity().owningKey,b.info.singleIdentity().owningKey)
//        // We check the recorded transaction in both transaction storages.
//        for (node in listOf(a, b)) {
//            assertEquals(signedTx, node.services.validatedTransactions.getTransaction(signedTx.id))
//        }
//
//        println("// signed transaction")
//        println(signedTx)
//        // Public key of all participant in signed transaction
//        println("// Set of require signer")
//        println(signedTx.requiredSigningKeys)
//        println("// Service of node")
//        println("// Get transaction id from node (input transaction id parameter) case : sign TX")
//        // Id of sign transaction
//        println(signedTx.id)
//        println(a.services.validatedTransactions.getTransaction(signedTx.id))
//        // Get sign transaction for node
//        val recordedTx = a.services.validatedTransactions.getTransaction(signedTx.id)
//        // !! converts any value to a non-null type and throws an exception if the value is null
//        val txOutputs = recordedTx!!.tx.outputs
//        // Array of output state
//        println("// txoutput")
//        println(signedTx.tx.outputs)
//        println(txOutputs)
//        // output of signed transaction (sign by a and b)
//        val recordedState = txOutputs[0].data as IOUState
//        println("// Output state")
//        println(recordedState)
//        println(signedTx.tx.outputs[0])
//
//        println("// ---------------------------------------")
//        println("// Value in output state")
//        println("// ---------------------------------------")
//        println("// Value of iou")
//        println(recordedState.value)
//        println("// Borrower of iou")
//        println(recordedState.borrower)
//        println("// Lender of iou")
//        println(recordedState.lender)
//        // Query state from vault
//        val myStates = a.services.vaultService.queryBy<IOUState>().states
//        println("// Vault query")
//        println(myStates)
//
//    }

    @Test
    fun `Bond Create test`() {

        println("// ---------------------------------------")
        println("// Initial flow")
        println("// ---------------------------------------")
        val flow = BondFlow_Issue.Initiator(1, b.info.singleIdentity())
        val future = a.startFlow(flow)
        network.runNetwork()
        println("// Node info")
        println(a.info)
        println("// Node identity (Party)")
        println(a.info.singleIdentity())
        println("// Owning key(Public key)")
        println(a.info.singleIdentity().owningKey)
        println(future)
        println("// Future output")
        println(future.get())

//        println("// ---------------------------------------")
//        println("// Check exception")
//        println("// ---------------------------------------")
//        // assertFailsWith -> specific exception (have exception it pass , dont have it fail)
//        val except = assertFailsWith<TransactionVerificationException> { future.getOrThrow() }
//        println("Exception message")
//        println(except.message)
//        println("Exception cause")
//        println(except.cause)

        println("// ---------------------------------------")
        println("// Signed transaction")
        println("// ---------------------------------------")
        val signedTx = future.getOrThrow()
        signedTx.verifySignaturesExcept(a.info.singleIdentity().owningKey,b.info.singleIdentity().owningKey)
        println("// list of state ref")
        val check = signedTx.tx.outputs[0]
        val keep = check
        println(check.data)
        for (node in listOf(a, b)) {
            assertEquals(signedTx, node.services.validatedTransactions.getTransaction(signedTx.id))
        }
        val recordedTx = a.services.validatedTransactions.getTransaction(signedTx.id)
        val txOutputs = recordedTx!!.tx.outputs
        val recordedState = txOutputs[0].data as BondState
        println(signedTx)

        println("// ---------------------------------------")
        println("// Value in output state")
        println("// ---------------------------------------")

        val myStates = a.services.vaultService.queryBy<LinearState>()
        println("// Vault query")
        println(myStates)

//        val queryCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(campaignReference))
//        val campaignInputStateAndRef = serviceHub.vaultService.queryBy<Campaign>(queryCriteria).states.single()
    }

}