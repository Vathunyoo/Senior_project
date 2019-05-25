package com.example.schema

import net.corda.core.contracts.Amount
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.finance.USD
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
            @Column(name = "amount")
            var amount: Long,

            @Column(name = "currency")
            var currency: String,

            @Column(name = "owner")
            var ownerName: String,

            @Column(name = "lender")
            var lenderName: String,

            @Column(name = "escrow")
            var escrowName: String,

            @Column(name = "interest")
            var interest: Double,

            @Column(name = "period")
            var period: Int,

            @Column(name = "status")
            var status: String,

            @Column(name = "date")
            var date: Instant,

            @Column(name = "bond_id")
            var bondId: UUID
    ) : PersistentState() {
        // Default constructor required by hibernate.
//        constructor(): this("", "", "",0, Instant.now(),UUID.randomUUID())
        constructor(): this(0, "","","","",0.0,0,"",Instant.now(),UUID.randomUUID())
    }
}

