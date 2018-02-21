package piuk.blockchain.android.data.bitcoincash

import info.blockchain.api.blockexplorer.BlockExplorer
import info.blockchain.api.data.UnspentOutput
import info.blockchain.wallet.BitcoinCashWallet
import info.blockchain.wallet.coin.GenericMetadataAccount
import info.blockchain.wallet.coin.GenericMetadataWallet
import info.blockchain.wallet.crypto.DeterministicAccount
import info.blockchain.wallet.multiaddress.TransactionSummary
import info.blockchain.wallet.payload.data.LegacyAddress
import io.reactivex.Completable
import io.reactivex.Observable
import piuk.blockchain.android.R
import piuk.blockchain.android.data.api.EnvironmentSettings
import piuk.blockchain.android.data.metadata.MetadataManager
import piuk.blockchain.android.data.payload.PayloadDataManager
import piuk.blockchain.android.data.rxjava.RxBus
import piuk.blockchain.android.data.rxjava.RxPinning
import piuk.blockchain.android.data.rxjava.RxUtil
import piuk.blockchain.android.util.MetadataUtils
import piuk.blockchain.android.util.StringUtils
import piuk.blockchain.android.util.annotations.Mockable
import piuk.blockchain.android.util.annotations.WebRequest
import timber.log.Timber
import java.math.BigInteger
import java.util.*

@Mockable
class BchDataManager(
        private val payloadDataManager: PayloadDataManager,
        private val bchDataStore: BchDataStore,
        private val metadataUtils: MetadataUtils,
        private val environmentSettings: EnvironmentSettings,
        private val blockExplorer: BlockExplorer,
        private val stringUtils: StringUtils,
        private val metadataManager: MetadataManager,
        rxBus: RxBus
) {

    private val rxPinning = RxPinning(rxBus)

    /**
     * Clears the currently stored BCH wallet from memory.
     */
    fun clearBchAccountDetails() = bchDataStore.clearBchData()

    /**
     * Fetches EthereumWallet stored in metadata. If metadata entry doesn't exists it will be created.
     *
     * @param defaultLabel The ETH address default label to be used if metadata entry doesn't exist
     * @return An [Completable]
     */
    fun initBchWallet(defaultLabel: String): Completable =
            rxPinning.call {
                fetchOrCreateBchMetadata(defaultLabel)
                        .flatMapCompletable {
                            restoreBchWallet(it.first!!)
                                    .mergeWith(
                                            if (it.second)
                                                metadataManager.saveToMetadata(bchDataStore.bchMetadata!!.toJson(), BitcoinCashWallet.METADATA_TYPE_EXTERNAL)
                                            else
                                                Completable.complete()
                                    )
                        }
                        .andThen(correctBtcOffsetIfNeed(stringUtils.getString(R.string.default_wallet_name)))
            }.compose(RxUtil.applySchedulersToCompletable())

    /**
     * Refreshes bitcoin cash metadata. Useful if another platform performed any changes to wallet state.
     * At this point metadataNodeFactory.metadata node will exist.
     */
    fun refreshMetadataCompletable(): Completable = initBchWallet(stringUtils.getString(R.string.bch_default_account_label))

    fun serializeForSaving(): String = bchDataStore.bchMetadata!!.toJson()

    private fun fetchOrCreateBchMetadata(
            defaultLabel: String
    ) = metadataManager.fetchMetadata(BitcoinCashWallet.METADATA_TYPE_EXTERNAL)
            .compose(RxUtil.applySchedulersToObservable())
            .map { optional ->

                val walletJson = optional.value

                var needsSave = false

                val accountTotal = payloadDataManager.accounts.size

                val metaData: GenericMetadataWallet

                if (walletJson.isNullOrEmpty()) {
                    // Create
                    val bchAccounts = getMetadataAccounts(defaultLabel, 0, accountTotal)

                    metaData = GenericMetadataWallet().apply {
                        accounts = bchAccounts
                        isHasSeen = true
                    }

                    needsSave = true
                } else {
                    //Fetch wallet
                    metaData = GenericMetadataWallet.fromJson(walletJson)

                    //Sanity check (Add missing metadata accounts)
                    metaData?.accounts?.run {
                        val bchAccounts = getMetadataAccounts(defaultLabel, size, accountTotal)
                        addAll(bchAccounts)
                    }
                }

                if (bchDataStore.bchMetadata == null || !listContentEquals(bchDataStore.bchMetadata!!.accounts, metaData.accounts)) {
                    bchDataStore.bchMetadata = metaData
                } else {
                    // metadata list unchanged
                }

                Timber.d("vos 2")

                needsSave
            }.map {
                Pair(bchDataStore.bchMetadata, it)
            }

    fun listContentEquals(listA: MutableList<GenericMetadataAccount>, listB: MutableList<GenericMetadataAccount>): Boolean {

        listA.forEach{ accountA ->
            val filteredItems = listB.filter { accountB ->
                (accountB.label == accountA.label) && (accountB.isArchived == accountA.isArchived)
            }

            if (filteredItems.size == 0) {
                return false
            }
        }

        return true
    }

    private fun getMetadataAccounts(
            defaultLabel: String,
            startingAccountIndex: Int,
            accountTotal: Int
    ): ArrayList<GenericMetadataAccount> {
        val bchAccounts = arrayListOf<GenericMetadataAccount>()
        ((startingAccountIndex + 1)..accountTotal)
                .map {
                    return@map when (it) {
                        in 2..accountTotal -> defaultLabel + " " + it
                        else -> defaultLabel
                    }
                }
                .forEach { bchAccounts.add(GenericMetadataAccount(it, false)) }

        return bchAccounts
    }

    /**
     * Restore bitcoin cash wallet
     */
    private fun restoreBchWallet(walletMetadata: GenericMetadataWallet) =
            Completable.fromCallable {
                if (!payloadDataManager.isDoubleEncrypted) {
                    bchDataStore.bchWallet = BitcoinCashWallet.restore(
                            blockExplorer,
                            environmentSettings.bitcoinCashNetworkParameters,
                            BitcoinCashWallet.BITCOIN_COIN_PATH,
                            payloadDataManager.mnemonic,
                            ""
                    )

                    // BCH Metadata does not store xpub - get from btc wallet since PATH is the same
                    payloadDataManager.accounts.forEachIndexed { i, account ->
                        bchDataStore.bchWallet?.addAccount()
                        walletMetadata.accounts[i].xpub = account.xpub
                    }
                } else {

                    bchDataStore.bchWallet = BitcoinCashWallet.createWatchOnly(
                            blockExplorer,
                            environmentSettings.bitcoinCashNetworkParameters
                    )

                    // NB! A watch-only account xpub != account xpub, they do however derive the same addresses.
                    // Only use this [DeterministicAccount] to derive receive/change addresses. Don't use xpub as multiaddr etc parameter.
                    payloadDataManager.accounts.forEachIndexed { i, account ->
                        bchDataStore.bchWallet?.addWatchOnlyAccount(account.xpub)
                        walletMetadata.accounts[i].xpub = account.xpub
                    }
                }

            }.compose(RxUtil.applySchedulersToCompletable())

    /**
     * Create more btc accounts to catch up to BCH stored in metadata if required.
     *
     * BCH metadata might have more accounts than a restored BTC wallet. When a BTC wallet is restored
     * from mnemonic we will only look ahead 5 accounts to see if the account contains any transactions.
     *
     * @param Default bitcoin account label
     * @return Boolean value to indicate if bitcoin wallet payload needs to sync to the server
     */
    fun correctBtcOffsetIfNeed(defaultBtcLabel: String) =
            Completable.fromCallable {
                val startingAccountIndex = payloadDataManager.accounts.size
                val accountTotal = bchDataStore.bchMetadata?.accounts?.size?.minus(startingAccountIndex)
                        ?: 0

                if (accountTotal > 0) {
                    (startingAccountIndex..accountTotal!!)
                            .map {
                                return@map defaultBtcLabel + " " + it
                            }
                            .forEachIndexed { i, s ->

                                val accountIndex = i + startingAccountIndex
                                val accountNumber = i + startingAccountIndex + 1

                                val acc = payloadDataManager.wallet.hdWallets[0].addAccount(defaultBtcLabel + " " + accountNumber)

                                bchDataStore.bchMetadata!!.accounts[accountIndex]?.apply {
                                    this.xpub = acc.xpub
                                }
                            }
                }

                val needsSave = accountTotal > 0

                if (needsSave) {
                    payloadDataManager.syncPayloadWithServer()
                } else {
                    Completable.complete()
                }
            }.compose(RxUtil.applySchedulersToCompletable())

    /**
     * Restore bitcoin cash wallet from mnemonic.
     */
    fun decryptWatchOnlyWallet(mnemonic: List<String>) {

        bchDataStore.bchWallet = BitcoinCashWallet.restore(
                blockExplorer,
                environmentSettings.bitcoinCashNetworkParameters,
                BitcoinCashWallet.BITCOIN_COIN_PATH,
                mnemonic,
                ""
        )

        payloadDataManager.accounts.forEachIndexed { i, account ->
            bchDataStore.bchWallet?.addAccount()
            bchDataStore.bchMetadata!!.accounts[i].xpub = account.xpub
        }
    }

    /**
     * Adds a [GenericMetadataAccount] to the BCH wallet. The wallet will have to be saved at this
     * point. This assumes that a new [info.blockchain.wallet.payload.data.Account] has already
     * been added to the user's Payload, otherwise xPubs could get out of sync.
     */
    fun createAccount(bitcoinXpub: String) {

        if (bchDataStore.bchWallet!!.isWatchOnly) {
            bchDataStore.bchWallet!!.addWatchOnlyAccount(bitcoinXpub)
        } else {
            bchDataStore.bchWallet!!.addAccount()
        }

        val defaultLabel = stringUtils.getString(R.string.bch_default_account_label)
        val count = bchDataStore.bchWallet!!.accountTotal
        bchDataStore.bchMetadata!!.addAccount(
                GenericMetadataAccount(
                        """$defaultLabel $count""",
                        false
                ).apply { xpub = bitcoinXpub }
        )
    }

    fun getActiveXpubs(): List<String> =
            bchDataStore.bchMetadata?.accounts?.filterNot { it.isArchived }?.map { it.xpub }
                    ?: emptyList()

    fun getActiveXpubsAndImportedAddresses(): List<String> = getActiveXpubs().toMutableList()
            .apply { addAll(getLegacyAddressStringList()) }

    fun getLegacyAddressStringList(): List<String> = payloadDataManager.legacyAddressStringList

    fun getWatchOnlyAddressStringList(): List<String> =
            payloadDataManager.watchOnlyAddressStringList

    fun updateAllBalances(): Completable {
        val legacyAddresses = payloadDataManager.legacyAddresses
                .filterNot { it.isWatchOnly || it.tag == LegacyAddress.ARCHIVED_ADDRESS }
                .map { it.address }
        val all = getActiveXpubs().plus(legacyAddresses)
        return rxPinning.call { bchDataStore.bchWallet!!.updateAllBalances(legacyAddresses, all) }
                .compose(RxUtil.applySchedulersToCompletable())
    }

    fun getAddressBalance(address: String): BigInteger =
            bchDataStore.bchWallet?.getAddressBalance(address) ?: BigInteger.ZERO

    fun getWalletBalance(): BigInteger =
            bchDataStore.bchWallet?.getWalletBalance() ?: BigInteger.ZERO

    fun getImportedAddressBalance(): BigInteger =
            bchDataStore.bchWallet?.getImportedAddressBalance() ?: BigInteger.ZERO

    fun getAddressTransactions(
            address: String,
            limit: Int,
            offset: Int
    ): Observable<List<TransactionSummary>> =
            rxPinning.call<List<TransactionSummary>> {
                Observable.fromCallable { fetchAddressTransactions(address, limit, offset) }
            }.compose(RxUtil.applySchedulersToObservable())

    fun getWalletTransactions(limit: Int, offset: Int): Observable<List<TransactionSummary>> =
            rxPinning.call<List<TransactionSummary>> {
                Observable.fromCallable { fetchWalletTransactions(limit, offset) }
            }.compose(RxUtil.applySchedulersToObservable())

    fun getImportedAddressTransactions(
            limit: Int,
            offset: Int
    ): Observable<List<TransactionSummary>> =
            rxPinning.call<List<TransactionSummary>> {
                Observable.fromCallable { fetchImportedAddressTransactions(limit, offset) }
            }.compose(RxUtil.applySchedulersToObservable())

    /**
     * Returns all non-archived accounts
     * @return Generic account data that contains label and xpub/address
     */
    fun getActiveAccounts(): List<GenericMetadataAccount> {
        return getAccountMetadataList().filterNot { it.isArchived }
    }

    fun getAccountMetadataList(): List<GenericMetadataAccount> =bchDataStore.bchMetadata?.accounts ?: emptyList()

    fun getAccountList(): List<DeterministicAccount> = bchDataStore.bchWallet!!.accounts

    fun getDefaultAccountPosition(): Int = bchDataStore.bchMetadata?.defaultAcccountIdx ?: 0

    fun setDefaultAccountPosition(position: Int) {
        bchDataStore.bchMetadata!!.defaultAcccountIdx = position
    }

    fun getDefaultDeterministicAccount(): DeterministicAccount? =
            bchDataStore.bchWallet?.accounts?.get(getDefaultAccountPosition())

    fun getDefaultGenericMetadataAccount(): GenericMetadataAccount? =
            getAccountMetadataList()?.get(getDefaultAccountPosition())

    /**
     * Allows you to generate a BCH receive address at an arbitrary number of positions on the chain
     * from the next valid unused address. For example, the passing 5 as the position will generate
     * an address which correlates with the next available address + 5 positions.
     *
     * @param accountIndex  The index of the [GenericMetadataAccount] you wish to generate an address from
     * @param addressIndex Represents how many positions on the chain beyond what is already used that
     * you wish to generate
     * @return A Bitcoin Cash receive address in Base58 format
     */
    fun getReceiveAddressAtPosition(accountIndex: Int, addressIndex: Int): String? =
            bchDataStore.bchWallet?.getReceiveAddressAtPosition(accountIndex, addressIndex)

    /**
     * Generates a Base58 Bitcoin Cash receive address for an account at a given position. The
     * address returned will be the next unused in the chain.
     *
     * @param accountIndex The index of the [DeterministicAccount] you wish to generate an address from
     * @return A Bitcoin Cash receive address in Base58 format
     */
    fun getNextReceiveAddress(accountIndex: Int): Observable<String> =
            Observable.fromCallable {
                bchDataStore.bchWallet!!.getNextReceiveAddress(accountIndex)
            }

    /**
     * Generates a bech32 Bitcoin Cash receive address for an account at a given position. The
     * address returned will be the next unused in the chain.
     *
     * @param accountIndex The index of the [DeterministicAccount] you wish to generate an address from
     * @return A Bitcoin Cash receive address in bech32 format
     */
    fun getNextReceiveCashAddress(accountIndex: Int): Observable<String> =
            Observable.fromCallable {
                bchDataStore.bchWallet!!.getNextReceiveCashAddress(accountIndex)
            }

    /**
     * Generates a Base58 Bitcoin Cash change address for an account at a given position. The
     * address returned will be the next unused in the chain.
     *
     * @param accountIndex The index of the [DeterministicAccount] you wish to generate an address from
     * @return A Bitcoin Cash change address in Base58 format
     */
    fun getNextChangeAddress(accountIndex: Int): Observable<String> =
            Observable.fromCallable {
                bchDataStore.bchWallet!!.getNextChangeAddress(accountIndex)
            }

    /**
     * Generates a bech32 Bitcoin Cash change address for an account at a given position. The
     * address returned will be the next unused in the chain.
     *
     * @param accountIndex The index of the [DeterministicAccount] you wish to generate an address from
     * @return A Bitcoin Cash change address in bech32 format
     */
    fun getNextChangeCashAddress(accountIndex: Int): Observable<String> =
            Observable.fromCallable {
                bchDataStore.bchWallet!!.getNextChangeCashAddress(accountIndex)
            }

    /**
     * Allows you to generate a BCH change address at an arbitrary number of positions on the chain
     * from the next valid unused address. For example, the passing 5 as the position will generate
     * an address which correlates with the next available address + 5 positions.
     *
     * @param accountIndex  The index of the [Account] you wish to generate an address from
     * @param addressIndex Represents how many positions on the chain beyond what is already used that
     * you wish to generate
     * @return A Bitcoin Cash change address in Base58 format
     */
    fun getChangeAddressAtPosition(accountIndex: Int, addressIndex: Int): Observable<String> =
            Observable.fromCallable {
                bchDataStore.bchWallet!!.getChangeAddressAtPosition(accountIndex, addressIndex)
            }

    fun incrementNextReceiveAddress(xpub: String): Completable =
            Completable.fromCallable {
                bchDataStore.bchWallet!!.incrementNextReceiveAddress(xpub)
            }

    fun incrementNextChangeAddress(xpub: String): Completable =
            Completable.fromCallable {
                bchDataStore.bchWallet!!.incrementNextChangeAddress(xpub)
            }

    fun isOwnAddress(address: String) = bchDataStore.bchWallet?.isOwnAddress(address) ?: false

    /**
     * Converts any Bitcoin Cash address to a label.
     *
     * @param address Accepts account receive or change chain address, as well as legacy address.
     * @return Account or legacy address label
     */
    fun getLabelFromBchAddress(address: String): String? {
        val xpub = bchDataStore.bchWallet?.getXpubFromAddress(address)

        return bchDataStore.bchMetadata?.accounts?.find { it.xpub == xpub }?.label
    }

    ///////////////////////////////////////////////////////////////////////////
    // Web requests that require wrapping in Observables
    ///////////////////////////////////////////////////////////////////////////

    @WebRequest
    private fun fetchAddressTransactions(
            address: String,
            limit: Int,
            offset: Int
    ): MutableList<TransactionSummary> =
            bchDataStore.bchWallet!!.getTransactions(
                    null,//legacy list
                    mutableListOf(),//watch-only list
                    getActiveXpubsAndImportedAddresses(),
                    address,
                    limit,
                    offset
            )

    @WebRequest
    private fun fetchWalletTransactions(limit: Int, offset: Int): MutableList<TransactionSummary> =
            bchDataStore.bchWallet!!.getTransactions(
                    null,//legacy list
                    mutableListOf(),//watch-only list
                    getActiveXpubsAndImportedAddresses(),
                    null,
                    limit,
                    offset
            )

    @WebRequest
    private fun fetchImportedAddressTransactions(
            limit: Int,
            offset: Int
    ): MutableList<TransactionSummary> =
            bchDataStore.bchWallet!!.getTransactions(
                    payloadDataManager.legacyAddressStringList,//legacy list
                    mutableListOf(),//watch-only list
                    getActiveXpubsAndImportedAddresses(),
                    null,
                    limit,
                    offset
            )

    fun getXpubFromAddress(address: String) =
            bchDataStore.bchWallet!!.getXpubFromAddress(address)

    fun getHDKeysForSigning(
            account: DeterministicAccount,
            unspentOutputs: List<UnspentOutput>
    ) = bchDataStore.bchWallet!!.getHDKeysForSigning(account, unspentOutputs)

    fun subtractAmountFromAddressBalance(account: String, amount: BigInteger) =
            bchDataStore.bchWallet!!.subtractAmountFromAddressBalance(account, amount)

}
