package com.ampnet.tradeservice.blockchain

import com.ampnet.tradeservice.blockchain.properties.ChainPropertiesHandler
import com.ampnet.tradeservice.configuration.ApplicationProperties
import com.ampnet.tradeservice.exception.ErrorCode
import com.ampnet.tradeservice.exception.InternalException
import mu.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestTemplate
import org.springframework.web.client.getForObject
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Uint
import org.web3j.crypto.RawTransaction
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.Response
import org.web3j.tx.RawTransactionManager
import org.web3j.utils.Convert
import java.io.IOException
import java.math.BigInteger

private val logger = KotlinLogging.logger {}

@Service
class BlockchainService(
    applicationProperties: ApplicationProperties,
    private val restTemplate: RestTemplate
) {

    private val chainHandler = ChainPropertiesHandler(applicationProperties)

    @Suppress("MagicNumber")
    private val gasLimit = BigInteger.valueOf(2_000_000)

    @Throws(InternalException::class)
    fun settle(
        chainId: Long,
        orderId: BigInteger,
        usdAmount: BigInteger,
        tokenAmount: BigInteger,
        wallet: String
    ): String? {
        logger.info {
            "Settle: [on chainId=$chainId for orderId=$orderId in usdAmount=$usdAmount " +
                "for tokenAmount=$tokenAmount to wallet=$wallet]"
        }
        val blockchainProperties = chainHandler.getBlockchainProperties(chainId)
        val nonce = blockchainProperties.web3j
            .ethGetTransactionCount(blockchainProperties.credentials.address, DefaultBlockParameterName.LATEST)
            .sendSafely()?.transactionCount ?: return null
        val gasPrice = getGasPrice(chainId)
        logger.debug { "Gas price: $gasPrice" }

        val function = Function(
            "settle",
            listOf(wallet.toAddress(), orderId.toUint(), usdAmount.toUint(), tokenAmount.toUint()),
            emptyList()
        )
        val rawTransaction = RawTransaction.createTransaction(
            nonce, gasPrice, gasLimit, blockchainProperties.chain.orderBookAddress, FunctionEncoder.encode(function)
        )

        val manager = RawTransactionManager(blockchainProperties.web3j, blockchainProperties.credentials, chainId)
        val sentTransaction = blockchainProperties.web3j
            .ethSendRawTransaction(manager.sign(rawTransaction)).sendSafely()
        logger.info {
            "Successfully send to settle: [on chainId=$chainId for orderId=$orderId in usdAmount=$usdAmount " +
                "for tokenAmount=$tokenAmount to wallet=$wallet txHash=${sentTransaction?.transactionHash}]"
        }
        return sentTransaction?.transactionHash
    }

    @Throws(InternalException::class)
    fun isMined(hash: String, chainId: Long): Boolean {
        val web3j = chainHandler.getBlockchainProperties(chainId).web3j
        val transaction = web3j.ethGetTransactionReceipt(hash).sendSafely()
        return transaction?.transactionReceipt?.isPresent ?: false
    }

    @Throws(InternalException::class)
    fun getBlockNumber(chainId: Long): BigInteger {
        val chainProperties = chainHandler.getBlockchainProperties(chainId)
        return chainProperties.web3j.ethBlockNumber().sendSafely()?.blockNumber
            ?: throw InternalException(ErrorCode.BLOCKCHAIN_JSON_RPC, "Failed to fetch latest block number")
    }

    // 
    internal fun getGasPrice(chainId: Long): BigInteger? {

        chainHandler.getGasPriceFeed(chainId)?.let { url ->
            try {
                val response = restTemplate
                    .getForObject<GasPriceFeedResponse>(url, GasPriceFeedResponse::class)
                response.fastest?.let { price ->
                    val gWei = Convert.toWei(price.toString(), Convert.Unit.GWEI).toBigInteger()
                    logger.debug { "Fetched gas price in GWei: $gWei" }
                    return gWei
                }
            } catch (ex: RestClientException) {
                logger.warn { "Failed to get price for feed: $url" }
            }
        }
        val mumbaiChainId = 80001L
        when(chainId) {
            mumbaiChainId -> {
                return Convert.toWei("6", Convert.Unit.GWEI).toBigInteger()
            }
            else -> {
                return chainHandler.getBlockchainProperties(chainId)
                    .web3j.ethGasPrice().sendSafely()?.gasPrice
            }
        }
    }

    private data class GasPriceFeedResponse(
        val safeLow: Double?,
        val standard: Double?,
        val fast: Double?,
        val fastest: Double?,
        val blockTime: Long?,
        val blockNumber: Long?
    )
}

@Suppress("ReturnCount")
fun <S, T : Response<*>?> Request<S, T>.sendSafely(): T? {
    try {
        val value = this.send()
        if (value?.hasError() == true) {
            logger.warn { "Web3j call errors: ${value.error.message}" }
            return null
        }
        return value
    } catch (ex: IOException) {
        logger.warn("Failed blockchain call", ex)
        return null
    }
}

@Suppress("MagicNumber")
fun String.toAddress(): Address = Address(160, this)

fun BigInteger.toUint(): Uint = Uint(this)
