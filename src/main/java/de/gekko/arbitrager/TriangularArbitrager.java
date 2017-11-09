package de.gekko.arbitrager;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.currency.Currency;
import org.knowm.xchange.dto.account.Wallet;
import org.knowm.xchange.dto.marketdata.OrderBook;
import org.knowm.xchange.exceptions.ExchangeException;
import org.knowm.xchange.exceptions.NotAvailableFromExchangeException;
import org.knowm.xchange.exceptions.NotYetImplementedForExchangeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.gekko.exception.CurrencyMismatchException;
import de.gekko.exchanges.AbstractArbitrageExchange;

public class TriangularArbitrager {
	
	private int arbitCounter = 0;
	private boolean debug = true;
	
	/**
	 * Speichert den Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger("Arbitrager");
	
	/**
	 * Speichert die Exchange
	 */
	private AbstractArbitrageExchange exchange;
	private OrderBook orderbook1;
	private OrderBook orderbook2;
	private OrderBook orderbook3;
	private Wallet exchangeWallet;
	
	/**
	 * Speichert die Currency Pairs
	 */
	private CurrencyPair basePair;
	private CurrencyPair crossPair1;
	private boolean twistCrossPair1 = false;
	private CurrencyPair crossPair2;
	private boolean twistCrossPair2 = false;
	
	private Map<Currency, Double> balanceMap = new HashMap<>();
	
	/**
	 * Speichert den Executor Service für Netzwerkanfragen auf die Exchange.
	 */
	private ExecutorService networkExecutorService = Executors.newFixedThreadPool(3);
	
	boolean updateWallets = true;
	
	public TriangularArbitrager(AbstractArbitrageExchange exchange, CurrencyPair basePair,
			CurrencyPair crossPair1, CurrencyPair crossPair2) throws IOException, CurrencyMismatchException {
		
		this.exchange = exchange;
		this.basePair = basePair;
		this.crossPair1 = crossPair1;
		this.crossPair2 = crossPair2;
		alignCurrencyPairs();

		updateWallet();
		System.out.println(twistCrossPair1);
		System.out.println(twistCrossPair2);

	}

	/**
	 * Align currencyPairs if possible, else throw exception.
	 * @throws CurrencyMismatchException
	 */
	public void alignCurrencyPairs() throws CurrencyMismatchException {
		checkSwitchCrossPairs();
		if(!(checkBaseCross1Alignment() && checkBaseCross2Alignment() && checkCrossAlignment())){
			throw new CurrencyMismatchException();
		}
	}
	
	/**
	 * Checks if Crosspairs have to be switched for currency pair alignment.
	 * @return
	 */
	private boolean checkSwitchCrossPairs() {
		boolean switched = false;
		boolean counterCross1Match = basePair.counter.equals(crossPair1.base) || basePair.counter.equals(crossPair1.counter);
		boolean baseCross2Match = basePair.base.equals(crossPair2.base) || basePair.base.equals(crossPair2.counter);
		if(counterCross1Match && baseCross2Match) {
			CurrencyPair tmp = crossPair1;
			crossPair1 = crossPair2;
			crossPair2 = tmp;
			switched = true;
		}
		return switched;
	}
	
	/**
	 * Checks for currency alignment between base and cross currency #1. Sets alignment variable if necessary.
	 * @return 
	 */
	private boolean checkBaseCross1Alignment() {
		boolean alignmentPossible = false;

		if (basePair.base.equals(crossPair1.base)) {
			twistCrossPair1 = false;
			alignmentPossible = true;
		} else {
			if (basePair.base.equals(crossPair1.counter)) {
				twistCrossPair1 = true;
				alignmentPossible = true;
			}
		}

		return alignmentPossible;
	}
	
	/**
	 * Checks for currency alignment between base and cross currency #2. Sets alignment variable if necessary.
	 * @return 
	 */
	private boolean checkBaseCross2Alignment() {
		boolean alignmentPossible = false;

		if (basePair.counter.equals(crossPair2.counter)) {
			twistCrossPair2 = false;
			alignmentPossible = true;
		} else {
			if (basePair.counter.equals(crossPair2.base)) {
				twistCrossPair2 = true;
				alignmentPossible = true;
			}
		}

		return alignmentPossible;
	}
	
	/**
	 * Checks for alignment between cross currency #1 and cross currency #2.
	 * @return 
	 */
	private boolean checkCrossAlignment(){
		boolean aligned = false;
		
		if(!twistCrossPair1 && !twistCrossPair2){
			if(crossPair1.counter.equals(crossPair2.base)){
				aligned = true;
			}
		}
		if(!twistCrossPair1 && twistCrossPair2){
			if(crossPair1.counter.equals(crossPair2.counter)){
				aligned = true;
			}
		}
		if(twistCrossPair1 && !twistCrossPair2){
			if(crossPair1.base.equals(crossPair2.base)){
				aligned = true;
			}
		}
		if(twistCrossPair1 && twistCrossPair2){
			if(crossPair1.base.equals(crossPair2.counter)){
				aligned = true;
			}
		}

		return aligned;
	}
	
	public void triangularArbitrage() throws NotAvailableFromExchangeException, NotYetImplementedForExchangeException, ExchangeException, IOException, InterruptedException {
		updateOrderbooks();
		
		if(triangularArbitrage1()){
			updateOrderbooks();
			arbitCounter++;
		}
		if(triangularArbitrage2()){
			arbitCounter++;
		}
		LOGGER.info("Numer of Arbitrage Chances: {}", arbitCounter);
	}
	
	/**
	 * 
	 * @return
	 * @throws NotAvailableFromExchangeException
	 * @throws NotYetImplementedForExchangeException
	 * @throws ExchangeException
	 * @throws IOException
	 * @throws InterruptedException
	 */
	public boolean triangularArbitrage1() throws NotAvailableFromExchangeException, NotYetImplementedForExchangeException, ExchangeException, IOException, InterruptedException {
		// Return value for couting arbitrage chances
		boolean ret = false;
		// Get bids and asks
		double basePairPrice = orderbook1.getBids().get(0).getLimitPrice().doubleValue();
		double crossPair1Price;
		double crossPair2Price;
		if(twistCrossPair1){
			crossPair1Price = orderbook2.getBids().get(0).getLimitPrice().doubleValue();
		} else {
			crossPair1Price = orderbook2.getAsks().get(0).getLimitPrice().doubleValue();
		}
		if(twistCrossPair2){
			crossPair2Price = orderbook3.getBids().get(0).getLimitPrice().doubleValue();
		} else {
			crossPair2Price = orderbook3.getAsks().get(0).getLimitPrice().doubleValue();
		}

		// Calculate cross exchangerate and arbitrage
		double crossExchangeRate;
		if(twistCrossPair1){
			crossExchangeRate = (1/crossPair1Price)*crossPair2Price;
		} else {
			crossExchangeRate = crossPair1Price*(1/crossPair2Price);
		}
		double arb = (basePairPrice/crossExchangeRate -1)*100;
		
		// Abitrage info
		LOGGER.info("BID: {} [{}/{}] -- ASK: {} [X {}/{}] -- ARBITRAGE = {}", basePairPrice, basePair.base.toString(), basePair.counter.toString(), String.format("%.8f", crossExchangeRate), crossPair1.counter.toString(), crossPair2.counter.toString(), arb);

		
		if(debug){
			// Simulated trading for debugging and testing
			LOGGER.info("=== DEBUG TRADE #1 ===");
			double tradeAmount = 1;
			// Base pair
			double sellAmountBasePair = tradeAmount;
			double buyAmountBasePair = sellAmountBasePair*basePairPrice;
			LOGGER.info("Sell {}: {} for {}: {}", basePair.base, formatDecimals(sellAmountBasePair), basePair.counter, formatDecimals(buyAmountBasePair));
			
			// Cross pair 2
			double sellAmountCrossPair2;
			double buyAmountCrossPair2;
			if(twistCrossPair2) {
				sellAmountCrossPair2 = sellAmountBasePair*basePairPrice;
				buyAmountCrossPair2 = sellAmountCrossPair2*crossPair2Price;
				LOGGER.info("Sell {}: {} for {}: {}", crossPair2.base, formatDecimals(sellAmountCrossPair2), crossPair2.counter, formatDecimals(buyAmountCrossPair2));
			} else {
				sellAmountCrossPair2 = sellAmountBasePair*basePairPrice;
				buyAmountCrossPair2 = sellAmountCrossPair2/crossPair2Price;
				LOGGER.info("Sell {}: {} for {}: {}", crossPair2.counter, formatDecimals(sellAmountCrossPair2), crossPair2.base, formatDecimals(buyAmountCrossPair2));
			}
			
			// Cross pair 1
			double sellAmountCrossPair1;
			double buyAmountCrossPair1;
			if(twistCrossPair1) {
				sellAmountCrossPair1 = sellAmountCrossPair2/crossPair2Price;
				buyAmountCrossPair1 = sellAmountCrossPair1*crossPair1Price;
				LOGGER.info("Sell {}: {} for {}: {}", crossPair1.base, formatDecimals(sellAmountCrossPair1), crossPair1.counter, formatDecimals(buyAmountCrossPair1));
			} else {
				sellAmountCrossPair1 = sellAmountCrossPair2*crossPair2Price;
				buyAmountCrossPair1 = sellAmountCrossPair1/crossPair1Price;
				LOGGER.info("Sell {}: {} for {}: {}", crossPair1.counter, formatDecimals(sellAmountCrossPair1), crossPair1.base, formatDecimals(buyAmountCrossPair1));
			}

			LOGGER.info("DEBUG ARBITRAGE = {}", (buyAmountCrossPair1/sellAmountBasePair -1)*100);

			// if arbitrage chance exists set return value to true
			if ((arb - 0.75) > 0) {
				ret = true;
			}
			
		} else {
			if ((arb - 0.75) > 0) {
				System.out.println("=====> Arbitrage (with fees): " + String.format("%.8f", arb - 0.75));

				double tradeAmount = 0.01;

				// Set up order for base pair
				double sellAmountBasePair = tradeAmount;
				Callable<String> callable_orderBasePair = () -> {
					return exchange.placeLimitOrderAsk(basePair, basePairPrice, sellAmountBasePair);
				};

				
				// Set up order for cross pair 2
				double sellAmountCrossPair2 = sellAmountBasePair*basePairPrice;
				Callable<String> callable_orderCrossPair2;
				if(twistCrossPair2) {
					callable_orderCrossPair2 = () -> {
						return exchange.placeLimitOrderBid(crossPair2, crossPair2Price, sellAmountCrossPair2);
					};
				} else {
					callable_orderCrossPair2 = () -> {
						return exchange.placeLimitOrderAsk(crossPair2, crossPair2Price, sellAmountCrossPair2);
					};
				}
				
				//TODO
				// Set up orders for cross pair 1
				Callable<String> callable_orderCrossPair1;
				if(twistCrossPair1) {
					double sellAmountCrossPair1 = sellAmountCrossPair2/crossPair2Price;
					callable_orderCrossPair1 = () -> {
						return exchange.placeLimitOrderBid(crossPair1, crossPair1Price, sellAmountCrossPair1);
					};	
				} else {
					double sellAmountCrossPair1 = sellAmountCrossPair2*crossPair2Price;
					callable_orderCrossPair1 = () -> {
						return exchange.placeLimitOrderAsk(crossPair1, crossPair1Price, sellAmountCrossPair1);
					};	
				}
				
				// Send orders
				Future<String> future_order1 = networkExecutorService.submit(callable_orderBasePair);
				Future<String> future_order2 = networkExecutorService.submit(callable_orderCrossPair1);
				Future<String> future_order3 = networkExecutorService.submit(callable_orderCrossPair2);

				// Order IDs
				String orderID_trade1 = "";
				String orderID_trade2 = "";
				String orderID_trade3 = "";
				try {
					// Get order IDs
					orderID_trade1 = future_order1.get();
					LOGGER.info("Order1 Placed. ID: {}", orderID_trade1);
					orderID_trade2 = future_order2.get();
					LOGGER.info("Order2 Placed. ID: {}", orderID_trade2);
					orderID_trade3 = future_order3.get();
					LOGGER.info("Order2 Placed. ID: {}", orderID_trade3);
				} catch (Exception e) {
					e.printStackTrace();
					// If something went wrong, try to cancel remaining orders
					boolean cancelledTrade1 = exchange.cancelOrder(orderID_trade1);
					boolean cancelledTrade2 = exchange.cancelOrder(orderID_trade2);
					boolean cancelledTrade3 = exchange.cancelOrder(orderID_trade3);
					System.err.println("Fatal trade error, terminating application.");
					System.exit(1);
				}
				ret = true;
			}
		}
		return ret;
	}
	
	public boolean triangularArbitrage2() throws NotAvailableFromExchangeException, NotYetImplementedForExchangeException, ExchangeException, IOException, InterruptedException {
		// Return value
		boolean ret = false;
		
		double currencyPair1Price = orderbook1.getAsks().get(0).getLimitPrice().doubleValue();
		double currencyPair2Price;
		double currencyPair3Price;
		if(twistCrossPair1){
			currencyPair2Price = orderbook2.getAsks().get(0).getLimitPrice().doubleValue();
		} else {
			currencyPair2Price = orderbook2.getBids().get(0).getLimitPrice().doubleValue();
		}
		if(twistCrossPair1){
			currencyPair3Price = orderbook3.getAsks().get(0).getLimitPrice().doubleValue();
		} else {
			currencyPair3Price = orderbook3.getBids().get(0).getLimitPrice().doubleValue();
		}
		
		// Calculate cross exchangerate and arbitrage
		double crossExchangeRate;
		if(twistCrossPair1){
			crossExchangeRate = (1/currencyPair2Price)*currencyPair3Price;
		} else {
			crossExchangeRate = currencyPair2Price*(1/currencyPair3Price);
		}
		double arb = (crossExchangeRate/currencyPair1Price -1)*100;

		LOGGER.info("ASK: {} [{}/{}] -- BID: {} [X {}/{}] -- ARBITRAGE = {}", currencyPair1Price, basePair.base.toString(), basePair.counter.toString(), String.format("%.8f", crossExchangeRate), crossPair1.counter.toString(), crossPair2.counter.toString(), arb);

		if(debug){
			// Simulated trading for debugging and testing
			LOGGER.info("=== DEBUG TRADE #2 ===");
			double tradeAmount = 1;
			// Base pair
			double sellBasePair = tradeAmount*currencyPair1Price;
			double buyBasePair = tradeAmount;
			LOGGER.info("Sell {}: {} for {}: {}", basePair.counter, formatDecimals(sellBasePair), basePair.base, formatDecimals(buyBasePair));
			
			// Cross pair 1
			double sellCrossPair1;
			double buyCrossPair1;
			if(twistCrossPair1) {
				sellCrossPair1 = tradeAmount;
				buyCrossPair1 = sellCrossPair1/currencyPair2Price;
				LOGGER.info("Sell {}: {} for {}: {}", crossPair1.counter, formatDecimals(sellCrossPair1), crossPair1.base, formatDecimals(buyCrossPair1));
			} else {
				sellCrossPair1 = tradeAmount;
				buyCrossPair1 = sellCrossPair1*currencyPair2Price;
				LOGGER.info("Sell {}: {} for {}: {}", crossPair1.base, formatDecimals(sellCrossPair1), crossPair1.counter, formatDecimals(buyCrossPair1));
			}

			// Cross pair 2
			double sellCrossPair2;
			double buyCrossPair2;
			if(twistCrossPair2) {
				sellCrossPair2 = sellCrossPair1*currencyPair2Price;
				buyCrossPair2 = sellCrossPair2/currencyPair3Price;
				LOGGER.info("Sell {}: {} for {}: {}", crossPair2.counter, formatDecimals(sellCrossPair2), crossPair2.base, formatDecimals(buyCrossPair2));
			} else {
				sellCrossPair2 = sellCrossPair1/currencyPair2Price;
				buyCrossPair2 = sellCrossPair2*currencyPair3Price;
				LOGGER.info("Sell {}: {} for {}: {}", crossPair2.base, formatDecimals(sellCrossPair2), crossPair2.counter, formatDecimals(buyCrossPair2));
			}
			
			LOGGER.info("DEBUG ARBITRAGE = {}", (buyCrossPair2/sellBasePair -1)*100);
			
			if((arb - 0.75) > 0) {
				ret = true;
			}
			
		} else {
		
		if((arb - 0.75) > 0) {
			System.out.println("=====> Arbitrage2 (with fees): " + String.format("%.8f", arb - 0.75));
			
			double tradeAmount = 0.01;
			
			Callable<String> callable_order1 = () -> {
				return exchange.placeLimitOrderBid(basePair, currencyPair1Price, tradeAmount); // ETH für BTC kaufen
			};

			Callable<String> callable_order2 = () -> {
				return exchange.placeLimitOrderBid(crossPair1, currencyPair2Price, (tradeAmount/currencyPair2Price)); //NEO für ETH verkaufen
			};
			Callable<String> callable_order3 = () -> {
				return exchange.placeLimitOrderAsk(crossPair2, currencyPair3Price, tradeAmount*currencyPair1Price); //NEO für BTC kaufen
			};

//			Future<String> future_order1 = networkExecutorService.submit(callable_order1);
//			Future<String> future_order2 = networkExecutorService.submit(callable_order2);
//			Future<String> future_order3 = networkExecutorService.submit(callable_order3);

			String orderID_trade1 = "";
			String orderID_trade2 = "";
			String orderID_trade3 = "";
			try {
//				orderID_trade1 = future_order1.get();
//				LOGGER.info("Order1 Placed. ID: {}", orderID_trade1);
//				orderID_trade2 = future_order2.get();
//				LOGGER.info("Order2 Placed. ID: {}", orderID_trade2);
//				orderID_trade3 = future_order3.get();
//				LOGGER.info("Order2 Placed. ID: {}", orderID_trade3);

			} catch (Exception e) {
				e.printStackTrace();
				// boolean exchange1_cancelled =
				// exchange1.cancelOrder(orderID_exchange1);
				// boolean exchange2_cancelled =
				// exchange2.cancelOrder(orderID_exchange1);
				System.err.println("Fatal trade error, terminating application.");
				System.exit(1);
			}
			Thread.sleep(2000);
			//updateWallets = true;
			//updateWallet();
			ret = true;

		}
		}
		return ret;
		
	}
	
	/**
	 * Aktualisiert Orderbooks.
	 * 
	 * @throws NotAvailableFromExchangeException
	 * @throws NotYetImplementedForExchangeException
	 * @throws ExchangeException
	 * @throws IOException
	 */
	public void updateOrderbooks() throws NotAvailableFromExchangeException, NotYetImplementedForExchangeException,
			ExchangeException, IOException {
		LOGGER.info("Updating Orderbooks...");

		// Multi-threaded Implementation
		Callable<OrderBook> callable_Orderbook1 = () -> {
			return exchange.fetchOrderbook(basePair);
		};

		Callable<OrderBook> callable_Orderbook2 = () -> {
			return exchange.fetchOrderbook(crossPair1);
		};
		
		Callable<OrderBook> callable_Orderbook3 = () -> {
			return exchange.fetchOrderbook(crossPair2);
		};

		Future<OrderBook> future_Orderbook1 = networkExecutorService.submit(callable_Orderbook1);
		Future<OrderBook> future_Orderbook2 = networkExecutorService.submit(callable_Orderbook2);
		Future<OrderBook> future_Orderbook3 = networkExecutorService.submit(callable_Orderbook3);

		try {
			orderbook1 = future_Orderbook1.get();
//			LOGGER.info("[{}] Orderbook: ask = {}", exchange1, exchange1Orderbook.getAsks().get(0));
//			LOGGER.info("[{}] Orderbook: bid = {}", exchange1, exchange1Orderbook.getBids().get(0));
			orderbook2 = future_Orderbook2.get();
//			LOGGER.info("[{}] Orderbook: ask = {}", exchange2, exchange2Orderbook.getAsks().get(0));
//			LOGGER.info("[{}] Orderbook: bid = {}", exchange2, exchange2Orderbook.getBids().get(0));
			orderbook3 = future_Orderbook3.get();

		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (ExecutionException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	/**
	 * Kontostände aktualisieren.
	 * 
	 * @throws NotAvailableFromExchangeException
	 * @throws NotYetImplementedForExchangeException
	 * @throws ExchangeException
	 * @throws IOException
	 */
	public void updateWallet() throws NotAvailableFromExchangeException, NotYetImplementedForExchangeException,
			ExchangeException, IOException {
		if (updateWallets) {
			LOGGER.info("Updating Balances...");

			exchangeWallet = exchange.fetchWallet();
			
			for(Currency currency : getCurrencies()){
				double currencyBalance = exchangeWallet.getBalance(currency).getAvailable().doubleValue();
				
				double old;
				if(balanceMap.containsKey(currency)){
					old = balanceMap.get(currency);
				} else {
					old = currencyBalance;
				}
				balanceMap.put(currency, currencyBalance);
				LOGGER.info("[{}, {}] Balance: {}, Change: {}", exchange, currency, currencyBalance, String.format("%.8f", currencyBalance-old));
			}

			// Kontostände wieder aktuell, deswegen false.
			updateWallets = false;
		}
	}
	
	public Set<Currency> getCurrencies(){
		Set<Currency> currencies = new HashSet<>();
		currencies.add(basePair.base);
		currencies.add(basePair.counter);
		currencies.add(crossPair1.base);
		currencies.add(crossPair1.counter);
		currencies.add(crossPair2.base);
		currencies.add(crossPair2.counter);
		return currencies;
	}
	
	public Set<CurrencyPair> getCurrencyPairs(){
		Set<CurrencyPair> currencyPairs = new HashSet<>();
		currencyPairs.add(basePair);
		currencyPairs.add(crossPair1);
		currencyPairs.add(crossPair2);
		return currencyPairs;		
	}
	
	public String formatDecimals(double doubleVal) {
		return String.format("%." + exchange.getDecimals() + "f", doubleVal);
	}
}
