package com.example.state

import com.example.flow.BondFlow_Redeem
import com.example.schema.BlacklistSchemaV1
import net.corda.core.contracts.*
import net.corda.core.flows.FlowLogicRefFactory
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState
import java.util.*

data class BlacklistState (val backlist: Party,
                           val escrow: Party,
                           val point: Int,
                            override val linearId: UniqueIdentifier = UniqueIdentifier()): // Linear id of bond
        LinearState, QueryableState {

    override val participants: List<AbstractParty> get() = listOf(backlist, escrow)

    override fun generateMappedObject(schema: MappedSchema): PersistentState {
        return when (schema) {
            is BlacklistSchemaV1 -> BlacklistSchemaV1.PersistentBond(
                    this.backlist.name.toString(),
                    this.escrow.name.toString(),
                    this.point,
                    this.linearId.id
            )
            else -> throw IllegalArgumentException("Unrecognised schema $schema")
        }
    }

    override fun supportedSchemas(): Iterable<MappedSchema> = listOf(BlacklistSchemaV1)
}