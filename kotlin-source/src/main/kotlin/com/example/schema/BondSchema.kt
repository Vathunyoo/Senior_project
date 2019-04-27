package com.example.schema

import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import java.time.Instant
import java.util.*
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Table

object BondSchema

object BondSchemaV1 : MappedSchema(
        schemaFamily = BondSchema.javaClass,
        version = 1,
        mappedTypes = listOf(PersistentBond::class.java)) {
    @Entity
    @Table(name = "bond_states")
    class PersistentBond(
            @Column(name = "owner")
            var ownerName: String,

//            @Column(name = "financial")
//            var financialName: String,

            @Column(name = "lender")
            var lenderName: String,

            @Column(name = "amount")
            var amount: Int,

//            @Column(name = "date")
//            var date: Instant,

            @Column(name = "bond_id")
            var bondId: UUID
    ) : PersistentState() {
        // Default constructor required by hibernate.
//        constructor(): this("", "", "",0, Instant.now(),UUID.randomUUID())
        constructor(): this("", "",0,UUID.randomUUID())
    }
}

