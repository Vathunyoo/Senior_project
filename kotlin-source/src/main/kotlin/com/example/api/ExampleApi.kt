package com.example.api

import com.example.flow.*
import com.example.flow.ExampleFlow.Initiator
import com.example.schema.IOUSchemaV1
import com.example.state.BlacklistState
import com.example.state.BondState
import com.example.state.IOUState
import net.corda.core.contracts.Amount
import net.corda.core.contracts.StateAndRef
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
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
import net.corda.finance.flows.CashPaymentFlow
import org.slf4j.Logger
import java.time.Instant
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
    // --------------------------
    // Node name
    // --------------------------
    @GET
    @Path("me")
    @Produces(MediaType.APPLICATION_JSON)
    fun me() = mapOf("me" to myLegalName)

    // --------------------------
    // Cash state
    // --------------------------
    object cashObj {
        var quatity : Long = 0
        var currency: String = "THB"
    }
    @GET
    @Path("cashTHB")
    @Produces(MediaType.APPLICATION_JSON)
    fun getCashTHB() : StateAndRef<Cash.State>{
        var listCash = rpcOps.vaultQuery(Cash.State::class.java).states
        var thbQuatity : Long = 0
        var thbCurrency : String

        for(stateRefCash in listCash){
            if(stateRefCash.state.data.amount.token.toString() == "THB"){
                thbQuatity = stateRefCash.state.data.amount.quantity
                return stateRefCash
            }
        }

        var cashObj1 : cashObj? = null
        cashObj1?.quatity = thbQuatity
        return listCash[0]
    }
    @GET
    @Path("cash")
    @Produces(MediaType.APPLICATION_JSON)
    fun getCash() = rpcOps.vaultQuery(Cash.State::class.java).states

    @GET
    @Path("cash-balances")
    @Produces(MediaType.APPLICATION_JSON)
    fun getCashBalances() = rpcOps.getCashBalances()

    @PUT
    @Path("issue-cash")
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

    @PUT
    @Path("payment-cash")
    fun selfIssueCash(@QueryParam(value = "amount") amount: Int,
                      @QueryParam(value = "currency") currency: String,
                      @QueryParam("paymentName") paymentName: CordaX500Name
    ): Response {

        // 1. Prepare issue request.
        val paymentAmount = Amount(amount.toLong() * 100, Currency.getInstance(currency))
        var paymentParty = rpcOps.wellKnownPartyFromX500Name(paymentName)
                ?: return Response.status(BAD_REQUEST).entity("Party named $paymentName cannot be found.\n").build()

        // 2. Start flow and wait for response.
        val (status, message) = try {
            val flowHandle = rpcOps.startFlowDynamic(CashPaymentFlow::class.java, paymentAmount,paymentParty)
            val result = flowHandle.use { it.returnValue.getOrThrow() }
            CREATED to result.stx.tx.outputs.single().data
        } catch (e: Exception) {
            BAD_REQUEST to e.message
        }

        // 3. Return the response.
        return Response.status(status).entity(message).build()
    }

    // --------------------------
    // All bond state
    // --------------------------
    @GET
    @Path("bond")
    @Produces(MediaType.APPLICATION_JSON)
    fun getBond() = rpcOps.vaultQueryBy<BondState>().states

    @PUT
    @Path("create-bond")
    fun createBond(@QueryParam("amount") amount: Int,
                  @QueryParam("currency") currency: String,
                  @QueryParam("lender") lender: CordaX500Name?,
                  @QueryParam("escrow") escrow: CordaX500Name?,
                  @QueryParam("interest") interest: Double,
                  @QueryParam("period") period: Int
    ): Response {
        val bondAmount = Amount(amount.toLong() * 100, Currency.getInstance(currency))
        if (amount <= 0) {
            return Response.status(BAD_REQUEST).entity("Query parameter 'amount' must be non-negative.\n").build()
        }
        if (lender == null) {
            return Response.status(BAD_REQUEST).entity("Query parameter 'lender' missing or has wrong format.\n").build()
        }
        if (escrow == null) {
            return Response.status(BAD_REQUEST).entity("Query parameter 'escrow' missing or has wrong format.\n").build()
        }
        if (interest <= 0) {
            return Response.status(BAD_REQUEST).entity("Query parameter 'interest' must be non-negative.\n").build()
        }
        if (period <= 0) {
            return Response.status(BAD_REQUEST).entity("Query parameter 'period' must be non-negative.\n").build()
        }
        var lenderParty = rpcOps.wellKnownPartyFromX500Name(lender)
                ?: return Response.status(BAD_REQUEST).entity("Party named $lender cannot be found.\n").build()

        var escrowParty = rpcOps.wellKnownPartyFromX500Name(escrow)
                ?: return Response.status(BAD_REQUEST).entity("Party named $escrow cannot be found.\n").build()

        return try {
            val signedTx = rpcOps.startTrackedFlow(BondFlow_Issue::Initiator, bondAmount,lenderParty,escrowParty,interest,period,Instant.now().plusSeconds(10000)).returnValue.getOrThrow()
            Response.status(CREATED).entity("Transaction id ${signedTx.id} committed to ledger.\n").build()

        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            Response.status(BAD_REQUEST).entity(ex.message!!).build()
        }
    }

    @PUT
    @Path("update-bond")
    fun updateBond(@QueryParam("amount") amount: Int,
                   @QueryParam("currency") currency: String,
                   @QueryParam("bondRef") bondRef: String
    ): Response {
        val bondAmount = Amount(amount.toLong() * 100, Currency.getInstance(currency))
        if (amount <= 0) {
            return Response.status(BAD_REQUEST).entity("Query parameter 'amount' must be non-negative.\n").build()
        }

        return try {
            val signedTx = rpcOps.startTrackedFlow(BondFlow_Update::Initiator, bondAmount, bondRef).returnValue.getOrThrow()
            Response.status(CREATED).entity("Transaction id ${signedTx.id} committed to ledger.\n").build()

        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            Response.status(BAD_REQUEST).entity(ex.message!!).build()
        }
    }

    @PUT
    @Path("redeem-bond")
    fun redeemBond(@QueryParam("bondRef") bondRef: String
    ): Response {
        return try {
            val signedTx = rpcOps.startTrackedFlow(BondFlow_Redeem::Initiator, bondRef).returnValue.getOrThrow()
            Response.status(CREATED).entity("Transaction id ${signedTx.id} committed to ledger.\n").build()

        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            Response.status(BAD_REQUEST).entity(ex.message!!).build()
        }
    }

    // --------------------------
    // All blacklist state
    // --------------------------
    @GET
    @Path("blacklist")
    @Produces(MediaType.APPLICATION_JSON)
    fun getBlacklist() = rpcOps.vaultQueryBy<BlacklistState>().states

    @PUT
    @Path("blacklist-issue")
    fun issueBlacklist(
            @QueryParam("blacklistName") blacklistName: CordaX500Name?,
            @QueryParam("escrow") escrow: CordaX500Name?
    ): Response {
        if (blacklistName == null) {
            return Response.status(BAD_REQUEST).entity("Query parameter 'blacklistName' missing or has wrong format.\n").build()
        }
        if (escrow == null) {
            return Response.status(BAD_REQUEST).entity("Query parameter 'escrow' missing or has wrong format.\n").build()
        }
        var blacklistParty = rpcOps.wellKnownPartyFromX500Name(blacklistName)
                ?: return Response.status(BAD_REQUEST).entity("Party named $blacklistName cannot be found.\n").build()

        var escrowParty = rpcOps.wellKnownPartyFromX500Name(escrow)
                ?: return Response.status(BAD_REQUEST).entity("Party named $escrow cannot be found.\n").build()
        return try {
            val signedTx = rpcOps.startTrackedFlow(Blacklist_Issue::Initiator, blacklistParty,escrowParty).returnValue.getOrThrow()
            Response.status(CREATED).entity("Transaction id ${signedTx.id} committed to ledger.\n").build()

        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            Response.status(BAD_REQUEST).entity(ex.message!!).build()
        }
    }

    @PUT
    @Path("blacklist-update")
    fun issueBlacklist(
            @QueryParam("blackRef") blackRef: String
    ): Response {
        return try {
            val signedTx = rpcOps.startTrackedFlow(Blacklist_Update::Initiator, blackRef).returnValue.getOrThrow()
            Response.status(CREATED).entity("Transaction id ${signedTx.id} committed to ledger.\n").build()

        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            Response.status(BAD_REQUEST).entity(ex.message!!).build()
        }
    }

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


    @GET
    @Path("ious")
    @Produces(MediaType.APPLICATION_JSON)
    fun getIOUs() = rpcOps.vaultQueryBy<IOUState>().states

    @POST
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