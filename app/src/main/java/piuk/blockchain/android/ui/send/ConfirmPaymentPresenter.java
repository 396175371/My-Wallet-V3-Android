package piuk.blockchain.android.ui.send;

import javax.inject.Inject;

import piuk.blockchain.android.data.cache.DynamicFeeCache;
import piuk.blockchain.android.data.datamanagers.SendDataManager;
import piuk.blockchain.android.injection.Injector;
import piuk.blockchain.android.ui.account.PaymentConfirmationDetails;
import piuk.blockchain.android.ui.base.BasePresenter;
import piuk.blockchain.android.ui.base.UiState;

public class ConfirmPaymentPresenter extends BasePresenter<ConfirmPaymentView> {

    private static final String AMOUNT_FORMAT = "%1$s %2$s (%3$s%4$s)";

    @Inject SendDataManager sendDataManager;
    @Inject DynamicFeeCache dynamicFeeCache;
    private PaymentConfirmationDetails paymentDetails;

    ConfirmPaymentPresenter() {
        Injector.getInstance().getDataManagerComponent().inject(this);
    }

    @Override
    public void onViewReady() {
        paymentDetails = getView().getPaymentDetails();

        if (paymentDetails == null) {
            getView().closeDialog();
            return;
        }

        getView().setFromLabel(paymentDetails.fromLabel);
        getView().setToLabel(paymentDetails.toLabel);
        getView().setAmount(String.format(AMOUNT_FORMAT,
                paymentDetails.btcAmount,
                paymentDetails.btcUnit,
                paymentDetails.fiatSymbol,
                paymentDetails.fiatAmount));
        getView().setFee(String.format(AMOUNT_FORMAT,
                paymentDetails.btcFee,
                paymentDetails.btcUnit,
                paymentDetails.fiatSymbol,
                paymentDetails.fiatFee));
        getView().setTotalBtc(paymentDetails.btcTotal + " " + paymentDetails.btcUnit);
        getView().setTotalFiat(paymentDetails.fiatSymbol + paymentDetails.fiatTotal);
        getView().setUiState(UiState.CONTENT);
    }

    void onChangeFeeClicked() {
        getView().onFeeChangeClicked(paymentDetails.btcSuggestedFee, paymentDetails.btcUnit);
    }

}
