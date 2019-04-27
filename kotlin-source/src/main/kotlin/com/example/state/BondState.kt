package com.example.state

import com.example.schema.BondSchemaV1
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState
import java.time.Instant

data class BondState (val amount: Int, // Amount bond with lender
                      val owner: Party, // Owner of bond
                      val lender: Party, // Lender of bond
//                      val financial: Party, // Financial relevant with bond
//                      val date: Instant, // Date (Create bond)
                      override val linearId: UniqueIdentifier = UniqueIdentifier()): // Linear id of bond
        LinearState, QueryableState {

    // Override participant to state (Linear state)
    override val participants: List<AbstractParty> get() = listOf(owner, lender)

    // Mapping to object (Queryable state and persistance state)
    override fun generateMappedObject(schema: MappedSchema): PersistentState {
        return when (schema) {
            is BondSchemaV1 -> BondSchemaV1.PersistentBond(
                    this.owner.name.toString(),
//                    this.financial.name.toString(),
                    this.lender.name.toString(),
                    this.amount,
//                    this.date,
                    this.linearId.id
            )
            else -> throw IllegalArgumentException("Unrecognised schema $schema")
        }
    }

    override fun supportedSchemas(): Iterable<MappedSchema> = listOf(BondSchemaV1)
}

