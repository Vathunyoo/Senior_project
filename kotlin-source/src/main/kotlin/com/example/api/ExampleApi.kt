package com.example.api

import com.example.flow.BondFlow_Issue
import com.example.flow.ExampleFlow
import com.example.flow.ExampleFlow.Initiator
import com.example.schema.IOUSchemaV1
import com.example.state.BondState
import com.example.state.IOUState
import net.corda.core.contracts.Amount
import net.corda.core.identity.CordaX500Name
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.startTrackedFlow
import net.corda.core.messaging.vaultQueryBy
import net.corda.core.node.services.IdentityService
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.builder
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.loggerFor
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.contracts.getCashBalances
import net.corda.finance.flows.CashIssueFlow
import org.slf4j.Logger
import java.util.*
import javax.ws.rs.*
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response
import javax.ws.rs.core.Response.Status.BAD_REQUEST
import javax.ws.rs.core.Response.Status.CREATED

val SERVICE_NAMES = listOf("Notary", "Network Map Service")

// This API is accessible from /api/example. All paths specified below are relative to it.
@Path("example")
class ExampleApi(private val rpcOps: CordaRPCOps) {
    private val myLegalName: CordaX500Name = rpcOps.nodeInfo().legalIdentities.first().name

    companion object {
        private val logger: Logger = loggerFor<ExampleApi>()
    }

    /**
     * Returns the node's name.
     */
    @GET
    @Path("me")
    @Produces(MediaType.APPLICATION_JSON)
    fun whoami() = mapOf("me" to myLegalName)

    /**
     * Returns all parties registered with the [NetworkMapService]. These names can be used to look up identities
     * using the [IdentityService].
     */
    @GET
    @Path("peers")
    @Produces(MediaType.APPLICATION_JSON)
    fun getPeers(): Map<String, List<CordaX500Name>> {
        val nodeInfo = rpcOps.networkMapSnapshot()
        return mapOf("peers" to nodeInfo
                .map { it.legalIdentities.first().name }
                //filter out myself, notary and eventual network map started by driver
                .filter { it.organisation !in (SERVICE_NAMES + myLegalName.organisation) })
    }

    /**
     * Displays all IOU states that exist in the node's vault.
     */
    @GET
    @Path("ious")
    @Produces(MediaType.APPLICATION_JSON)
    fun getIOUs() = rpcOps.vaultQueryBy<IOUState>().states

    @GET
    @Path("bond")
    @Produces(MediaType.APPLICATION_JSON)
    fun getBond() = rpcOps.vaultQueryBy<BondState>().states

    /**
     * Initiates a flow to agree an IOU between two parties.
     *
     * Once the flow finishes it will have written the IOU to ledger. Both the lender and the borrower will be able to
     * see it when calling /api/example/ious on their respective nodes.
     *
     * This end-point takes a Party name parameter as part of the path. If the serving node can't find the other party
     * in its network map cache, it will return an HTTP bad request.
     *
     * The flow is invoked asynchronously. It returns a future when the flow's call() method returns.
     */
    @PUT
    @Path("create-iou")
    fun createIOU(@QueryParam("iouValue") iouValue: Int, @QueryParam("partyName") partyName: CordaX500Name?): Response {
        if (iouValue <= 0) {
            return Response.status(BAD_REQUEST).entity("Query parameter 'iouValue' must be non-negative.\n").build()
        }
        if (partyName == null) {
            return Response.status(BAD_REQUEST).entity("Query parameter 'partyName' missing or has wrong format.\n").build()
        }
        val otherParty = rpcOps.wellKnownPartyFromX500Name(partyName)
                ?: return Response.status(BAD_REQUEST).entity("Party named $partyName cannot be found.\n").build()

        return try {
            val signedTx = rpcOps.startTrackedFlow(ExampleFlow::Initiator, iouValue, otherParty).returnValue.getOrThrow()
            Response.status(CREATED).entity("Transaction id ${signedTx.id} committed to ledger.\n").build()

        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            Response.status(BAD_REQUEST).entity(ex.message!!).build()
        }
    }

    @GET
    @Path("cash")
    @Produces(MediaType.APPLICATION_JSON)
    fun cash() = rpcOps.vaultQuery(Cash.State::class.java).states

    @GET
    @Path("cash-balances")
    @Produces(MediaType.APPLICATION_JSON)
    fun getCashBalances() = rpcOps.getCashBalances()

    @GET
    @Path("self-issue-cash")
    fun selfIssueCash(@QueryParam(value = "amount") amount: Int,
                      @QueryParam(value = "currency") currency: String): Response {

        // 1. Prepare issue request.
        val issueAmount = Amount(amount.toLong() * 100, Currency.getInstance(currency))
        val notary = rpcOps.notaryIdentities().firstOrNull() ?: throw IllegalStateException("Could not find a notary.")
        val issueRef = OpaqueBytes.of(0)
        val issueRequest = CashIssueFlow.IssueRequest(issueAmount, issueRef, notary)

        // 2. Start flow and wait for response.
        val (status, message) = try {
            val flowHandle = rpcOps.startFlowDynamic(CashIssueFlow::class.java, issueRequest)
            val result = flowHandle.use { it.returnValue.getOrThrow() }
            CREATED to result.stx.tx.outputs.single().data
        } catch (e: Exception) {
            BAD_REQUEST to e.message
        }

        // 3. Return the response.
        return Response.status(status).entity(message).build()
    }

    @GET
    @Path("issue-obligation")
    fun issueObligation(@QueryParam(value = "amount") amount: Int,
                        @QueryParam(value = "currency") currency: String,
                        @QueryParam(value = "party") party: String): Response {
        // 1. Get party objects for the counterparty.
        val lenderIdentity = rpcOps.partiesFromName(party, exactMatch = false).singleOrNull()
                ?: throw IllegalStateException("Couldn't lookup node identity for $party.")

        // 2. Create an amount object.
        val issueAmount = Amount(amount.toLong() * 100, Currency.getInstance(currency))

        // 3. Start the IssueObligation flow. We block and wait for the flow to return.
        val (status, message) = try {
            val flowHandle = rpcOps.startFlowDynamic(
                    BondFlow_Issue.Initiator::class.java,
                    issueAmount,
                    lenderIdentity
            )

            val result = flowHandle.use { it.returnValue.getOrThrow() }
            CREATED to "Transaction id ${result.id} committed to ledger.\n${result.tx.outputs.single().data}"
        } catch (e: Exception) {
            BAD_REQUEST to e.message
        }

        // 4. Return the result.
        return Response.status(status).entity(message).build()
    }

    @GET
    @Path("obligations")
    @Produces(MediaType.APPLICATION_JSON)
    fun obligations(): List<BondState> {
        val statesAndRefs = rpcOps.vaultQuery(BondState::class.java).states
        return statesAndRefs
                .map { stateAndRef -> stateAndRef.state.data }
                .map { state ->
                    // We map the anonymous lender and borrower to well-known identities if possible.
                    val possiblyWellKnownLender = rpcOps.wellKnownPartyFromAnonymous(state.lender) ?: state.lender
                    val possiblyWellKnownBorrower = rpcOps.wellKnownPartyFromAnonymous(state.owner) ?: state.owner
                    val possiblyWellKnownFinancial = rpcOps.wellKnownPartyFromAnonymous(state.financial) ?: state.financial

                    BondState(state.amount,
                            possiblyWellKnownBorrower,
                            possiblyWellKnownLender,
                            possiblyWellKnownFinancial,
                            state.duedate,
                            state.linearId)
                }
    }

//    @PUT
//    @Path("Issue_bond")
//    fun issuebond(@QueryParam("amount") iouValue: Int, @QueryParam("partyName") partyName: CordaX500Name?): Response {
//        if (iouValue <= 100) {
//            return Response.status(BAD_REQUEST).entity("Query parameter 'iouValue' must be non-negative.\n").build()
//        }
//        if (partyName == null) {
//            return Response.status(BAD_REQUEST).entity("Query parameter 'partyName' missing or has wrong format.\n").build()
//        }
//        val otherParty = rpcOps.wellKnownPartyFromX500Name(partyName) ?:
//        return Response.status(BAD_REQUEST).entity("Party named $partyName cannot be found.\n").build()
//
//        return try {
//            val signedTx = rpcOps.startTrackedFlow(BondFlow_Issue::Initiator, iouValue, otherParty).returnValue.getOrThrow()
//            Response.status(CREATED).entity("Transaction id ${signedTx.id} committed to ledger.\n").build()
//
//        } catch (ex: Throwable) {
//            logger.error(ex.message, ex)
//            Response.status(BAD_REQUEST).entity(ex.message!!).build()
//        }
//    }

	
	/**
     * Displays all IOU states that are created by Party.
     */
    @GET
    @Path("my-ious")
    @Produces(MediaType.APPLICATION_JSON)
    fun myious(): Response {
        val generalCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.ALL)
        val results = builder {
                var partyType = IOUSchemaV1.PersistentIOU::lenderName.equal(rpcOps.nodeInfo().legalIdentities.first().name.toString())
                val customCriteria = QueryCriteria.VaultCustomQueryCriteria(partyType)
                val criteria = generalCriteria.and(customCriteria)
                val results = rpcOps.vaultQueryBy<IOUState>(criteria).states
                return Response.ok(results).build()
        }
    }
}