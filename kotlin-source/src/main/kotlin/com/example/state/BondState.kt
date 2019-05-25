package com.example.state

import com.example.flow.BondFlow_Redeem
import com.example.schema.BondSchemaV1
import net.corda.core.contracts.*
import net.corda.core.flows.FlowLogicRefFactory
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState
import java.time.Instant
import java.util.*

data class BondState (val amount: Amount<Currency>, // Amount bond with lender
                      val owner: Party, // Owner of bond
                      val lender: Party, // Lender of bond
                      val escrow: Party, // Financial relevant with bond
                      val interest: Double,
                      val period: Int,
                      val status: String,
                      val duedate: Instant, // Date (Create bond)
                      override val linearId: UniqueIdentifier = UniqueIdentifier()): // Linear id of bond
        LinearState, QueryableState, SchedulableState {

    // Override participant to state (Linear state)
    override val participants: List<AbstractParty> get() = listOf(owner, lender, escrow)

    // Mapping to object (Queryable state and persistance state)
    override fun generateMappedObject(schema: MappedSchema): PersistentState {
        return when (schema) {
            is BondSchemaV1 -> BondSchemaV1.PersistentBond(
                    this.amount.quantity,
                    this.amount.token.toString(),
                    this.owner.name.toString(),
                    this.lender.name.toString(),
                    this.escrow.name.toString(),
                    this.interest,
                    this.period,
                    this.status,
                    this.duedate,
                    this.linearId.id
            )
            else -> throw IllegalArgumentException("Unrecognised schema $schema")
        }
    }

    override fun supportedSchemas(): Iterable<MappedSchema> = listOf(BondSchemaV1)

    override fun nextScheduledActivity(thisStateRef: StateRef, flowLogicRefFactory: FlowLogicRefFactory): ScheduledActivity? {
        return ScheduledActivity(flowLogicRefFactory.create(BondFlow_Redeem.Initiator::class.java, thisStateRef.txhash.toString()), duedate)
    }
}

