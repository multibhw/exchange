/*
 * This file is part of Bitsquare.
 *
 * Bitsquare is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bitsquare is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bitsquare. If not, see <http://www.gnu.org/licenses/>.
 */

package io.bitsquare.trade.protocol.trade;

import io.bitsquare.p2p.MailboxMessage;
import io.bitsquare.p2p.Message;
import io.bitsquare.p2p.Peer;
import io.bitsquare.trade.SellerAsTakerTrade;
import io.bitsquare.trade.Trade;
import io.bitsquare.trade.protocol.trade.messages.DepositTxPublishedMessage;
import io.bitsquare.trade.protocol.trade.messages.FiatTransferStartedMessage;
import io.bitsquare.trade.protocol.trade.messages.RequestPayDepositMessage;
import io.bitsquare.trade.protocol.trade.messages.TradeMessage;
import io.bitsquare.trade.protocol.trade.tasks.seller.CommitDepositTx;
import io.bitsquare.trade.protocol.trade.tasks.seller.CreateAndSignContract;
import io.bitsquare.trade.protocol.trade.tasks.seller.CreateAndSignDepositTx;
import io.bitsquare.trade.protocol.trade.tasks.seller.ProcessDepositTxPublishedMessage;
import io.bitsquare.trade.protocol.trade.tasks.seller.ProcessFiatTransferStartedMessage;
import io.bitsquare.trade.protocol.trade.tasks.seller.ProcessRequestPayDepositMessage;
import io.bitsquare.trade.protocol.trade.tasks.seller.SendPayoutTxFinalizedMessage;
import io.bitsquare.trade.protocol.trade.tasks.seller.SendRequestDepositTxInputsMessage;
import io.bitsquare.trade.protocol.trade.tasks.seller.SendRequestPublishDepositTxMessage;
import io.bitsquare.trade.protocol.trade.tasks.seller.SignAndFinalizePayoutTx;
import io.bitsquare.trade.protocol.trade.tasks.shared.SetupPayoutTxLockTimeReachedListener;
import io.bitsquare.trade.protocol.trade.tasks.taker.BroadcastTakeOfferFeeTx;
import io.bitsquare.trade.protocol.trade.tasks.taker.CreateTakeOfferFeeTx;
import io.bitsquare.trade.protocol.trade.tasks.taker.VerifyOfferFeePayment;
import io.bitsquare.trade.protocol.trade.tasks.taker.VerifyOffererAccount;
import io.bitsquare.trade.states.TakerTradeState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.bitsquare.util.Validator.nonEmptyStringOf;

public class SellerAsTakerProtocol extends TradeProtocol implements SellerProtocol, TakerProtocol {
    private static final Logger log = LoggerFactory.getLogger(SellerAsTakerProtocol.class);

    private final SellerAsTakerTrade sellerAsTakerTrade;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public SellerAsTakerProtocol(SellerAsTakerTrade trade) {
        super(trade.getProcessModel());
        log.debug("New SellerAsTakerProtocol " + this);
        this.sellerAsTakerTrade = trade;

        messageHandler = this::handleMessage;
        processModel.getMessageService().addMessageHandler(messageHandler);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Public methods
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void applyMailboxMessage(MailboxMessage mailboxMessage, Trade trade) {
        if (trade == null)
            this.trade = trade;

        log.debug("setMailboxMessage " + mailboxMessage);
        // Might be called twice, so check that its only processed once
        if (!processModel.isMailboxMessageProcessed()) {
            processModel.mailboxMessageProcessed();
            if (mailboxMessage instanceof FiatTransferStartedMessage) {
                handle((FiatTransferStartedMessage) mailboxMessage);
            }
            else if (mailboxMessage instanceof DepositTxPublishedMessage) {
                handle((DepositTxPublishedMessage) mailboxMessage);
            }
        }
    }

    @Override
    public void takeAvailableOffer() {
        TradeTaskRunner taskRunner = new TradeTaskRunner(sellerAsTakerTrade,
                () -> log.debug("taskRunner at takeAvailableOffer completed"),
                this::handleTaskRunnerFault);

        taskRunner.addTasks(
                CreateTakeOfferFeeTx.class,
                BroadcastTakeOfferFeeTx.class,
                SendRequestDepositTxInputsMessage.class
        );
        taskRunner.run();
        startTimeout();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Incoming message handling
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void handle(RequestPayDepositMessage tradeMessage) {
        log.debug("handle RequestPayDepositMessage");
        stopTimeout();
        processModel.setTradeMessage(tradeMessage);

        TradeTaskRunner taskRunner = new TradeTaskRunner(sellerAsTakerTrade,
                () -> log.debug("taskRunner at handleTakerDepositPaymentRequestMessage completed"),
                this::handleTaskRunnerFault);

        taskRunner.addTasks(
                ProcessRequestPayDepositMessage.class,
                VerifyOffererAccount.class,
                CreateAndSignContract.class,
                CreateAndSignDepositTx.class,
                SendRequestPublishDepositTxMessage.class
        );
        taskRunner.run();
        startTimeout();
    }

    private void handle(DepositTxPublishedMessage tradeMessage) {
        stopTimeout();
        processModel.setTradeMessage(tradeMessage);

        TradeTaskRunner taskRunner = new TradeTaskRunner(sellerAsTakerTrade,
                () -> log.debug("taskRunner at handleDepositTxPublishedMessage completed"),
                this::handleTaskRunnerFault);

        taskRunner.addTasks(
                ProcessDepositTxPublishedMessage.class,
                CommitDepositTx.class
        );
        taskRunner.run();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // After peer has started Fiat tx
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void handle(FiatTransferStartedMessage tradeMessage) {
        processModel.setTradeMessage(tradeMessage);

        TradeTaskRunner taskRunner = new TradeTaskRunner(sellerAsTakerTrade,
                () -> log.debug("taskRunner at handleFiatTransferStartedMessage completed"),
                this::handleTaskRunnerFault);

        taskRunner.addTasks(ProcessFiatTransferStartedMessage.class);
        taskRunner.run();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Called from UI
    ///////////////////////////////////////////////////////////////////////////////////////////

    // User clicked the "bank transfer received" button, so we release the funds for pay out
    @Override
    public void onFiatPaymentReceived() {
        sellerAsTakerTrade.setProcessState(TakerTradeState.ProcessState.FIAT_PAYMENT_RECEIVED);

        TradeTaskRunner taskRunner = new TradeTaskRunner(sellerAsTakerTrade,
                () -> {
                    log.debug("taskRunner at handleFiatReceivedUIEvent completed");

                    // we are done!
                    processModel.onComplete();
                },
                this::handleTaskRunnerFault);

        taskRunner.addTasks(
                VerifyOfferFeePayment.class,
                SignAndFinalizePayoutTx.class,
                SendPayoutTxFinalizedMessage.class,
                SetupPayoutTxLockTimeReachedListener.class
        );
        taskRunner.run();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Massage dispatcher
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void handleMessage(Message message, Peer sender) {
        log.trace("handleNewMessage: message = " + message.getClass().getSimpleName() + " from " + sender);
        if (message instanceof TradeMessage) {
            TradeMessage tradeMessage = (TradeMessage) message;
            nonEmptyStringOf(tradeMessage.tradeId);

            if (tradeMessage.tradeId.equals(processModel.getId())) {
                if (tradeMessage instanceof RequestPayDepositMessage) {
                    handle((RequestPayDepositMessage) tradeMessage);
                }
                else if (tradeMessage instanceof DepositTxPublishedMessage) {
                    handle((DepositTxPublishedMessage) tradeMessage);
                }
                else if (tradeMessage instanceof FiatTransferStartedMessage) {
                    handle((FiatTransferStartedMessage) tradeMessage);
                }
                else {
                    log.error("Incoming message not supported. " + tradeMessage);
                }
            }
        }
    }
}