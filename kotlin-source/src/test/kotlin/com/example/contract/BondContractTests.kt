package com.example.contract

import com.example.state.BondState
import com.example.contract.BondContract
import net.corda.core.contracts.Amount
import net.corda.core.identity.CordaX500Name
import net.corda.finance.USD
import net.corda.testing.core.TestIdentity
import net.corda.testing.node.MockServices
import net.corda.testing.node.ledger
import org.junit.Test
import java.time.Instant

class BondContractTests {
    // Generate mock services
    private val ledgerServices = MockServices()
    private val borrower = TestIdentity(CordaX500Name("MegaCorp", "London", "GB"))
    private val lender = TestIdentity(CordaX500Name("MegaCorp", "London", "GB"))
    private val financial = TestIdentity(CordaX500Name("MiniCorp", "New York", "US"))
    private val date = Instant.now()
    private val amount = Amount(1, USD)

    @Test
    fun `Check all component contract`() {
        ledgerServices.ledger {
            transaction {
                // Output insert (Contract_id ,State)
                output(BondContract.Bond_CONTRACT_ID, BondState(amount, borrower.party, lender.party))
                fails()
                // Command insert (list of signer,command)
                command(listOf(borrower.publicKey, lender.publicKey, financial.publicKey), BondContract.Commands.Issue())
                verifies()
            }
        }

    }

    @Test
    fun `transaction must include Issue command`() {
        ledgerServices.ledger {
            transaction {
                output(BondContract.Bond_CONTRACT_ID, BondState(amount, borrower.party, lender.party))
                fails()
                command(listOf(borrower.publicKey, lender.publicKey, financial.publicKey), BondContract.Commands.Issue())
                verifies()
            }
        }

    }

    @Test
    fun `transaction must include Update command`() {
        ledgerServices.ledger {
            ledgerServices.ledger {
                transaction {
                    output(BondContract.Bond_CONTRACT_ID, BondState(amount, borrower.party, lender.party))
                    fails()
                    command(listOf(borrower.publicKey, lender.publicKey, financial.publicKey), BondContract.Commands.Issue())
                    verifies()
                }

                transaction {
                    input(BondContract.Bond_CONTRACT_ID)
                    output(BondContract.Bond_CONTRACT_ID, BondState(amount, borrower.party, lender.party))
                    fails()
                    command(listOf(borrower.publicKey, lender.publicKey, financial.publicKey), BondContract.Commands.Update())
                    verifies()
                }
            }
        }

    }

    @Test
    fun `transaction must include Redeem command`() {
        ledgerServices.ledger {
            transaction {
                output(BondContract.Bond_CONTRACT_ID, BondState(amount, borrower.party, lender.party))
                fails()
                command(listOf(borrower.publicKey, lender.publicKey, financial.publicKey), BondContract.Commands.Issue())
                verifies()
            }
        }

    }

}