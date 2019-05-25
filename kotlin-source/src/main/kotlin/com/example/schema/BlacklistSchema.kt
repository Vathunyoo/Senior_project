package com.example.schema

import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import java.util.*
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Table

object BlacklistSchema

object BlacklistSchemaV1 : MappedSchema(
        schemaFamily = BlacklistSchema.javaClass,
        version = 1,
        mappedTypes = listOf(PersistentBond::class.java)) {
    @Entity
    @Table(name = "blacklist_states")
    class PersistentBond(
            @Column(name = "blacklist_party")
            var blackParty: String,

            @Column(name = "escrow")
            var escrowName: String,

            @Column(name = "black_point")
            var blackPoint: Int,

            @Column(name = "blacklist_id")
            var blacklistId: UUID
    ) : PersistentState() {
        constructor(): this("", "",0, UUID.randomUUID())
    }
}