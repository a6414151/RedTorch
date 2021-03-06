package xyz.redtorch.trader.module.zeus.strategy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;

import xyz.redtorch.trader.base.RtConstant;
import xyz.redtorch.trader.engine.event.EventConstant;
import xyz.redtorch.trader.engine.event.EventData;
import xyz.redtorch.trader.entity.Bar;
import xyz.redtorch.trader.entity.Contract;
import xyz.redtorch.trader.entity.Order;
import xyz.redtorch.trader.entity.OrderReq;
import xyz.redtorch.trader.entity.Tick;
import xyz.redtorch.trader.entity.Trade;
import xyz.redtorch.trader.module.zeus.ZeusConstant;
import xyz.redtorch.trader.module.zeus.ZeusEngine;
import xyz.redtorch.trader.module.zeus.entity.ContractPositionDetail;
import xyz.redtorch.trader.module.zeus.entity.PositionDetail;
import xyz.redtorch.trader.module.zeus.entity.StopOrder;
import xyz.redtorch.trader.module.zeus.strategy.StrategySetting.ContractTradeGatewaySetting;
import xyz.redtorch.trader.module.zeus.strategy.StrategySetting.TradeContractSetting;
import xyz.redtorch.utils.CommonUtil;

/**
 * 策略模板
 * 
 * @author sun0x00@gmail.com
 *
 */
public abstract class StrategyTemplate implements Strategy {

	private static final Logger log = LoggerFactory.getLogger(StrategyTemplate.class);

	LinkedBlockingQueue<EventData> eventDataQueue = new LinkedBlockingQueue<>();

	private String id; // 策略ID
	private String name; // 策略名称
	private String logStr; // 日志拼接字符串
	private boolean initStatus = false; // 初始化状态
	protected boolean trading = false; // 交易开关

	protected ZeusEngine zeusEngine; // 策略引擎

	protected StrategySetting strategySetting; // 策略配置

	protected Map<String, ContractPositionDetail> contractPositionMap = new HashMap<>(); // 合约仓位维护

	private Map<String, String> varMap = new HashMap<>(); // 变量

	private List<String> syncVarList = new ArrayList<String>(); // 存入数据库的变量

	private Map<String, String> paramMap = new HashMap<>(); // 参数

	Map<String, StopOrder> workingStopOrderMap = new HashMap<>(); // 本地停止单,停止单撤销后会被删除

	Map<String, Order> workingOrderMap = new HashMap<>(); // 委托单

	long stopOrderCount = 0L; // 停止单计数器

	HashSet<String> rtTradeIDSet = new HashSet<String>(); // 用于过滤可能重复的Trade推送

	// X分钟Bar生成器，由构造方法xMin参数决定是否实例化生效
	private Map<String, XMinBarGenerator> xMinBarGeneratorMap = new HashMap<>();
	private Map<String, BarGenerator> barGeneratorMap = new HashMap<>();

	/**
	 * 强制使用有参构造方法
	 * 
	 * @param id
	 *            策略ID
	 * @param name
	 *            策略名称
	 * @param xMin
	 *            分钟数，用于x分钟Bar生成器，范围[2,+∞)，建议此值不要大于120
	 * @param zeusEngine
	 */
	public StrategyTemplate(ZeusEngine zeusEngine, StrategySetting strategySetting) {
		strategySetting.fixSetting();
		this.strategySetting = strategySetting;
		this.paramMap.putAll(strategySetting.getParamMap());
		this.varMap.putAll(strategySetting.getVarMap());
		this.syncVarList.addAll(strategySetting.getSyncVarList());

		this.id = strategySetting.getId();
		this.name = strategySetting.getName();
		this.logStr = "Strategy:" + name + " ID:" + id;

		this.zeusEngine = zeusEngine;

		/**
		 * 初始化基本的持仓数据结构
		 */
		initContractPositionMap();

	}

	// 初始化持仓数据结构
	private void initContractPositionMap() {

		String tradingDay = strategySetting.getTradingDay();

		for (TradeContractSetting tradeContractSetting : strategySetting.getContracts()) {
			String rtSymbol = tradeContractSetting.getRtSymbol();
			String exchange = tradeContractSetting.getExchange();
			int contractSize = tradeContractSetting.getSize();

			ContractPositionDetail contractPositionDetail = new ContractPositionDetail(rtSymbol, tradingDay, name, id,
					exchange, contractSize);
			for (ContractTradeGatewaySetting contractTradeGatewaySetting : tradeContractSetting.getTradeGateways()) {
				String gatewayID = contractTradeGatewaySetting.getGatewayID();
				PositionDetail positionDetail = new PositionDetail(rtSymbol, contractTradeGatewaySetting.getGatewayID(),
						tradingDay, name, id, exchange, contractSize);
				contractPositionDetail.getPositionDetailMap().put(gatewayID, positionDetail);
			}
			contractPositionMap.put(rtSymbol, contractPositionDetail);
		}
	}

	@Override
	public void onEvent(EventData eventData) {
		if (eventData != null) {
			eventDataQueue.add(eventData);
		}
	}

	// 接口Runnable的实现方法，用于开启独立线程
	@Override
	public void run() {
		while (!Thread.currentThread().isInterrupted()) {
			EventData ed = null;
			try {
				ed = eventDataQueue.take();
			} catch (InterruptedException e) {
				stopTrading(true);
				log.error("{} 捕获到线程中断异常,停止策略！！！", logStr, e);
			}
			// 判断消息类型
			if (EventConstant.EVENT_TICK.equals(ed.getEventType())) {
				Tick tick = (Tick) ed.getEventObj();
				processTick(tick);

			} else if (EventConstant.EVENT_TRADE.equals(ed.getEventType())) {
				Trade trade = (Trade) ed.getEventObj();
				processTrade(trade);
			} else if (EventConstant.EVENT_ORDER.equals(ed.getEventType())) {
				Order order = (Order) ed.getEventObj();
				processOrder(order);
			} else if (EventConstant.EVENT_THREAD_STOP.equals(ed.getEventType())) {
				// 弃用
				// Thread.currentThread().interrupt();
				break;
			} else {
				log.warn("{} 未能识别的事件数据类型{}", logStr, JSON.toJSONString(ed));
			}
		}

		log.info("{} 策略线程准备结束！等待2秒", logStr);
		try {
			// 等待，防止异步线程没有完成
			Thread.sleep(500);
		} catch (InterruptedException e) {
			log.error("策略结束时线程异常", e);
		}
		log.info("{} 策略线程结束！", logStr);
	}

	/**
	 * 策略ID
	 * 
	 * @return
	 */
	@Override
	public String getID() {
		return id;
	}

	/**
	 * 获取策略名称
	 * 
	 * @return
	 */
	@Override
	public String getName() {
		return name;
	}

	/**
	 * 快捷获取日志拼接字符串
	 * 
	 * @return
	 */
	@Override
	public String getLogStr() {
		return logStr;
	}

	@Override
	public boolean isInitStatus() {
		return initStatus;
	}

	@Override
	public boolean isTrading() {
		return trading;
	}

	@Override
	public int getEngineType() {
		return this.zeusEngine.getEngineType();
	}

	@Override
	public StrategySetting getStrategySetting() {
		return strategySetting;
	}

	@Override
	public Map<String, ContractPositionDetail> getContractPositionMap() {
		return contractPositionMap;
	}

	@Override
	public Map<String, String> getVarMap() {
		return varMap;
	}

	@Override
	public Map<String, String> getParamMap() {
		return paramMap;
	}

	@Override
	public List<String> getSyncVarList() {
		return syncVarList;
	}

	@Override
	public void startTrading() {
		if (!initStatus) {
			log.warn("{} 策略尚未初始化,无法开始交易！", logStr);
			return;
		}

		if (trading) {
			log.warn("{} 策略正在运行,请勿重复操作！", logStr);
			return;
		}
		this.trading = true;
		try {
			onStartTrading();
			log.info("{} 开始交易", logStr);
		} catch (Exception e) {
			stopTrading(true);
			log.error("{} 调用onStartTrading发生异常,停止策略！！！", logStr, e);
		}
	}

	/**
	 * 停止交易
	 */
	@Override
	public void stopTrading(boolean isException) {
		if (!trading) {
			log.warn("{} 策略已经停止,请勿重复操作！", logStr);
			return;
		}
		this.trading = false;
		try {
			onStopTrading(isException);
		} catch (Exception e) {
			log.error("{} 策略停止后调用onStopTrading发生异常！", logStr, e);
		}
	}

	/**
	 * 完全关闭策略线程
	 */
	@Override
	public void stop() {
		stopTrading(false);
		// 通知其他线程
		EventData eventData = new EventData();
		eventData.setEvent(EventConstant.EVENT_THREAD_STOP);
		eventData.setEventType(EventConstant.EVENT_THREAD_STOP);
		eventDataQueue.add(eventData);
	}

	/**
	 * 初始化策略
	 */
	@Override
	public void init() {
		if (initStatus == true) {
			log.warn("{} 策略已经初始化,请勿重复操作！", logStr);
			return;
		}
		initStatus = true;
		try {
			onInit();
			log.info("{} 初始化", logStr);
		} catch (Exception e) {
			initStatus = false;
			log.error("{} 调用onInit发生异常！", logStr, e);
		}
	}

	@Override
	public void savePosition() {
		List<PositionDetail> positionDetailList = new ArrayList<>();
		for (ContractPositionDetail contractPositionDetail : contractPositionMap.values()) {
			positionDetailList.addAll(new ArrayList<>(contractPositionDetail.getPositionDetailMap().values()));
			zeusEngine.asyncSavePositionDetail(positionDetailList);
		}
	}

	@Override
	public void resetStrategy(StrategySetting strategySetting) {
		this.varMap.clear();
		this.workingStopOrderMap.clear();
		this.rtTradeIDSet.clear();
		this.paramMap.clear();
		this.contractPositionMap.clear();
		this.syncVarList.clear();

		strategySetting.fixSetting();
		this.strategySetting = strategySetting;
		this.paramMap.putAll(strategySetting.getParamMap());
		this.varMap.putAll(strategySetting.getVarMap());
		this.syncVarList.addAll(strategySetting.getSyncVarList());

		initContractPositionMap();

	}

	@Override
	public Map<String, StopOrder> getWorkingStopOrderMap() {
		return workingStopOrderMap;
	}

	@Override
	public String sendOrder(String rtSymbol, String orderType, String priceType, double price, int volume,
			String gatewayID) {

		String symbol;
		String exchange;
		double priceTick = 0;
		if (zeusEngine.getEngineType() == ZeusConstant.ENGINE_TYPE_BACKTESTING) {
			String[] rtSymbolArray = rtSymbol.split("\\.");
			symbol = rtSymbolArray[0];
			exchange = rtSymbolArray[1];
			priceTick = strategySetting.getContract(rtSymbol).getBacktestingPriceTick();
		} else {
			Contract contract = zeusEngine.getContract(rtSymbol, gatewayID);
			symbol = contract.getSymbol();
			exchange = contract.getExchange();
			priceTick = contract.getPriceTick();

		}

		OrderReq orderReq = new OrderReq();

		orderReq.setSymbol(symbol);
		orderReq.setExchange(exchange);
		orderReq.setRtSymbol(rtSymbol);
		orderReq.setPrice(CommonUtil.rountToPriceTick(priceTick, price));
		orderReq.setVolume(volume);
		orderReq.setGatewayID(gatewayID);

		orderReq.setPriceType(priceType);

		if (ZeusConstant.ORDER_BUY.equals(orderType)) {

			orderReq.setDirection(RtConstant.DIRECTION_LONG);
			orderReq.setOffset(RtConstant.OFFSET_OPEN);

		} else if (ZeusConstant.ORDER_SELL.equals(orderType)) {

			orderReq.setDirection(RtConstant.DIRECTION_SHORT);
			orderReq.setOffset(RtConstant.OFFSET_CLOSE);

		} else if (ZeusConstant.ORDER_SHORT.equals(orderType)) {

			orderReq.setDirection(RtConstant.DIRECTION_SHORT);
			orderReq.setOffset(RtConstant.OFFSET_OPEN);

		} else if (ZeusConstant.ORDER_COVER.equals(orderType)) {

			orderReq.setDirection(RtConstant.DIRECTION_LONG);
			orderReq.setOffset(RtConstant.OFFSET_CLOSE);

		} else if (ZeusConstant.ORDER_SELLTODAY.equals(orderType)) {

			orderReq.setDirection(RtConstant.DIRECTION_SHORT);
			orderReq.setOffset(RtConstant.OFFSET_CLOSETODAY);

		} else if (ZeusConstant.ORDER_SELLYESTERDAY.equals(orderType)) {

			orderReq.setDirection(RtConstant.DIRECTION_SHORT);
			orderReq.setOffset(RtConstant.OFFSET_CLOSEYESTERDAY);

		} else if (ZeusConstant.ORDER_COVERTODAY.equals(orderType)) {

			orderReq.setDirection(RtConstant.DIRECTION_LONG);
			orderReq.setOffset(RtConstant.OFFSET_CLOSETODAY);

		} else if (ZeusConstant.ORDER_COVERYESTERDAY.equals(orderType)) {

			orderReq.setDirection(RtConstant.DIRECTION_LONG);
			orderReq.setOffset(RtConstant.OFFSET_CLOSEYESTERDAY);
		}

		String rtOrderID = zeusEngine.sendOrder(orderReq, this);

		contractPositionMap.get(rtSymbol).updateOrderReq(orderReq, rtOrderID);

		return rtOrderID;
	}

	@Override
	public String sendStopOrder(String rtSymbol, String orderType, String priceType, double price, int volume,
			String gatewayID, Strategy strategy) {

		String stopOrderID = ZeusConstant.STOPORDERPREFIX + stopOrderCount + "." + id + "." + gatewayID;

		StopOrder stopOrder = new StopOrder();
		stopOrder.setRtSymbol(rtSymbol);
		stopOrder.setOrderType(orderType);
		double priceTick = 0;

		if (zeusEngine.getEngineType() == ZeusConstant.ENGINE_TYPE_BACKTESTING) {
			priceTick = strategySetting.getContract(rtSymbol).getBacktestingPriceTick();
		} else {
			priceTick = zeusEngine.getPriceTick(rtSymbol, gatewayID);
		}
		stopOrder.setPrice(CommonUtil.rountToPriceTick(priceTick, price));
		stopOrder.setVolume(volume);
		stopOrder.setStopOrderID(stopOrderID);
		stopOrder.setStatus(ZeusConstant.STOPORDER_WAITING);
		stopOrder.setGatewayID(gatewayID);
		stopOrder.setPriceType(priceType);

		if (ZeusConstant.ORDER_BUY.equals(orderType)) {

			stopOrder.setDirection(RtConstant.DIRECTION_LONG);
			stopOrder.setOffset(RtConstant.OFFSET_OPEN);

		} else if (ZeusConstant.ORDER_SELL.equals(orderType)) {

			stopOrder.setDirection(RtConstant.DIRECTION_SHORT);
			stopOrder.setOffset(RtConstant.OFFSET_CLOSE);

		} else if (ZeusConstant.ORDER_SHORT.equals(orderType)) {

			stopOrder.setDirection(RtConstant.DIRECTION_SHORT);
			stopOrder.setOffset(RtConstant.OFFSET_OPEN);

		} else if (ZeusConstant.ORDER_COVER.equals(orderType)) {

			stopOrder.setDirection(RtConstant.DIRECTION_LONG);
			stopOrder.setOffset(RtConstant.OFFSET_CLOSE);

		} else if (ZeusConstant.ORDER_SELLTODAY.equals(orderType)) {

			stopOrder.setDirection(RtConstant.DIRECTION_SHORT);
			stopOrder.setOffset(RtConstant.OFFSET_CLOSETODAY);

		} else if (ZeusConstant.ORDER_SELLYESTERDAY.equals(orderType)) {

			stopOrder.setDirection(RtConstant.DIRECTION_SHORT);
			stopOrder.setOffset(RtConstant.OFFSET_CLOSEYESTERDAY);

		} else if (ZeusConstant.ORDER_COVERTODAY.equals(orderType)) {

			stopOrder.setDirection(RtConstant.DIRECTION_LONG);
			stopOrder.setOffset(RtConstant.OFFSET_CLOSETODAY);

		} else if (ZeusConstant.ORDER_COVERYESTERDAY.equals(orderType)) {

			stopOrder.setDirection(RtConstant.DIRECTION_LONG);
			stopOrder.setOffset(RtConstant.OFFSET_CLOSEYESTERDAY);
		}

		workingStopOrderMap.put(stopOrderID, stopOrder);

		return stopOrderID;
	}

	public void cancelOrder(String rtOrderID) {
		if (StringUtils.isEmpty(rtOrderID)) {
			return;
		}
		if (workingOrderMap.containsKey(rtOrderID)) {
			zeusEngine.cancelOrder(rtOrderID);
			workingOrderMap.remove(rtOrderID);
		}
	}

	public void cancelStopOrder(String stopOrderID) {
		if (workingStopOrderMap.containsKey(stopOrderID)) {
			StopOrder stopOrder = workingStopOrderMap.get(stopOrderID);

			stopOrder.setStatus(ZeusConstant.STOPORDER_CANCELLED);

			workingStopOrderMap.remove(stopOrderID);

			try {
				onStopOrder(stopOrder);
			} catch (Exception e) {
				log.error("{} 通知策略StopOrder发生异常！！！", logStr, e);
				stopTrading(true);
			}

		}
	}

	public void cancelAll() {

		for (Entry<String, Order> entry : workingOrderMap.entrySet()) {
			String rtOrderID = entry.getKey();
			Order order = entry.getValue();
			if (!RtConstant.STATUS_FINISHED.contains(order.getStatus())) {
				cancelOrder(rtOrderID);
			}

		}

		for (Entry<String, StopOrder> entry : workingStopOrderMap.entrySet()) {
			String stopOrderID = entry.getKey();
			StopOrder stopOrder = entry.getValue();
			if (!ZeusConstant.STOPORDER_CANCELLED.equals(stopOrder.getStatus())) {
				cancelStopOrder(stopOrderID);
			}

		}
	}

	/**
	 * 处理停止单
	 * 
	 * @param tick
	 */
	protected void processStopOrder(Tick tick) {
		if (!trading) {
			return;
		}
		String rtSymbol = tick.getRtSymbol();

		for (StopOrder stopOrder : workingStopOrderMap.values()) {

			if (stopOrder.getRtSymbol().equals(rtSymbol)) {
				// 多头停止单触发
				boolean longTriggered = RtConstant.DIRECTION_LONG.equals(stopOrder.getDirection())
						&& tick.getLastPrice() >= stopOrder.getPrice();
				// 空头停止单触发
				boolean shortTriggered = RtConstant.DIRECTION_SHORT.equals(stopOrder.getDirection())
						&& tick.getLastPrice() <= stopOrder.getPrice();

				if (longTriggered || shortTriggered) {
					double price = 0;
					// 涨跌停价格报单
					if (RtConstant.DIRECTION_LONG.equals(stopOrder.getDirection())) {
						price = tick.getUpperLimit();
					} else {
						price = tick.getLowerLimit();
					}

					sendOrder(rtSymbol, stopOrder.getOrderType(), stopOrder.getPriceType(), price,
							stopOrder.getVolume(), stopOrder.getGatewayID());

					stopOrder.setStatus(ZeusConstant.STOPORDER_TRIGGERED);

					workingStopOrderMap.remove(stopOrder.getStopOrderID());

					try {
						onStopOrder(stopOrder);
					} catch (Exception e) {
						log.error("{} 通知策略StopOrder发生异常！！！", logStr, e);
						stopTrading(true);
					}
				}

			}
		}

	}

	@Override
	public void buy(String rtSymbol, int volume, double price, String gatewayID) {

		sendOrder(rtSymbol, ZeusConstant.ORDER_BUY, RtConstant.PRICETYPE_LIMITPRICE, price, volume, gatewayID);

	}

	@Override
	public void sell(String rtSymbol, int volume, double price, String gatewayID) {
		sendOrder(rtSymbol, ZeusConstant.ORDER_SELL, RtConstant.PRICETYPE_LIMITPRICE, price, volume, gatewayID);
	}

	@Override
	public void sellTd(String rtSymbol, int volume, double price, String gatewayID) {
		sendOrder(rtSymbol, ZeusConstant.ORDER_SELLTODAY, RtConstant.PRICETYPE_LIMITPRICE, price, volume, gatewayID);
	}

	@Override
	public void sellYd(String rtSymbol, int volume, double price, String gatewayID) {
		sendOrder(rtSymbol, ZeusConstant.ORDER_SELLYESTERDAY, RtConstant.PRICETYPE_LIMITPRICE, price, volume,
				gatewayID);
	}

	@Override
	public void sellShort(String rtSymbol, int volume, double price, String gatewayID) {
		sendOrder(rtSymbol, ZeusConstant.ORDER_SHORT, RtConstant.PRICETYPE_LIMITPRICE, price, volume, gatewayID);
	}

	@Override
	public void buyToCover(String rtSymbol, int volume, double price, String gatewayID) {
		sendOrder(rtSymbol, ZeusConstant.ORDER_COVER, RtConstant.PRICETYPE_LIMITPRICE, price, volume, gatewayID);

	}

	@Override
	public void buyToCoverTd(String rtSymbol, int volume, double price, String gatewayID) {
		sendOrder(rtSymbol, ZeusConstant.ORDER_COVERTODAY, RtConstant.PRICETYPE_LIMITPRICE, price, volume, gatewayID);

	}

	@Override
	public void buyToCoverYd(String rtSymbol, int volume, double price, String gatewayID) {
		sendOrder(rtSymbol, ZeusConstant.ORDER_COVERYESTERDAY, RtConstant.PRICETYPE_LIMITPRICE, price, volume,
				gatewayID);

	}

	@Override
	public void buyByPreset(String rtSymbol, double price) {

		ContractPositionDetail contractPositionDetail = contractPositionMap.get(rtSymbol);

		TradeContractSetting tradeContractSetting = strategySetting.getContract(rtSymbol);
		if (tradeContractSetting != null) {
			List<ContractTradeGatewaySetting> tradeGateways = tradeContractSetting.getTradeGateways();
			if (tradeGateways != null && !tradeGateways.isEmpty()) {
				if (contractPositionDetail != null) {
					int longPos = contractPositionDetail.getLongPos();
					int fixedPos = tradeContractSetting.getTradeFixedPos();
					if (longPos == fixedPos) {
						log.warn("合约{}的多头总持仓量已经达到预设值,指令终止！", rtSymbol);
						return;
					} else if (longPos > fixedPos) {
						log.error("合约{}的多头总持仓量{}已经超过预设值{},指令终止！！", rtSymbol);
						stopTrading(true);
						return;
					}
				}

				for (ContractTradeGatewaySetting tradeGteway : tradeGateways) {
					String gatewayID = tradeGteway.getGatewayID();
					int gatewayFixedPos = tradeGteway.getTradeFixedPos();
					int tradePos = gatewayFixedPos;
					if (gatewayFixedPos > 0) {
						PositionDetail positionDetail = contractPositionDetail.getPositionDetailMap().get(gatewayID);

						if (positionDetail != null) {
							int gatewayLongPos = positionDetail.getLongPos();
							int gatewayLongOpenFrozenPos = positionDetail.getLongOpenFrozen();
							if (gatewayLongPos + gatewayLongOpenFrozenPos == gatewayFixedPos) {
								log.warn("合约{}接口{}的多头持仓量加开仓冻结量已经达到预设值,指令忽略！", rtSymbol, gatewayID);
								continue;
							} else if (gatewayLongPos > gatewayFixedPos) {
								log.error("合约{}接口{}的多头持仓量{}加开仓冻结量{}已经超过预设值{},指令忽略！", rtSymbol, gatewayID,
										gatewayLongPos, gatewayLongOpenFrozenPos, gatewayFixedPos);
								stopTrading(true);
								continue;
							} else {
								tradePos = gatewayFixedPos - (gatewayLongPos + gatewayLongOpenFrozenPos);
							}
						}

						buy(rtSymbol, tradePos, price, gatewayID);
					} else {
						log.error("合约{}接口{}配置中的仓位大小不正确", rtSymbol, gatewayID);
						stopTrading(true);
					}
				}
			} else {
				log.error("未找到合约{}配置中的接口配置", rtSymbol);
				stopTrading(true);
			}
		} else {
			log.error("未找到合约{}的配置", rtSymbol);
			stopTrading(true);
		}

	}

	private void commonSellByPosition(String rtSymbol, double price, int offsetType) {
		ContractPositionDetail contractPositionDetail = contractPositionMap.get(rtSymbol);

		if (contractPositionDetail != null) {
			int longPos = contractPositionDetail.getLongPos();
			if (longPos == 0) {
				log.warn("合约{}的多头总持仓量为0,指令终止！", rtSymbol);
				return;
			} else if (longPos < 0) {
				log.error("合约{}的多头总持仓量{}小于0！", rtSymbol, longPos);
				stopTrading(true);
				return;
			}

			for (Entry<String, PositionDetail> entry : contractPositionDetail.getPositionDetailMap().entrySet()) {
				String gatewayID = entry.getKey();
				PositionDetail positionDetail = entry.getValue();
				if (positionDetail == null) {
					continue;
				}

				if (positionDetail.getLongPos() > 0) {
					if (offsetType >= 0) {
						if (positionDetail.getLongOpenFrozen() > 0) {
							log.warn("合约{}接口{}多头开仓冻结为{}，这部分不会被处理", rtSymbol, gatewayID,
									positionDetail.getLongOpenFrozen());
						}
						if (positionDetail.getLongTd() > 0) {
							sellTd(rtSymbol, positionDetail.getLongTd(), price, gatewayID);
						}
					}
					if (offsetType <= 0) {
						if (positionDetail.getLongYd() > 0) {
							sellYd(rtSymbol, positionDetail.getLongYd(), price, gatewayID);
						}
					}
				} else {
					log.error("合约{}接口{}多头持仓大小不正确", rtSymbol, gatewayID);
					stopTrading(true);
				}
			}
		} else {
			log.error("未找到合约{}的持仓信息", rtSymbol);
		}
	}

	@Override
	public void sellByPosition(String rtSymbol, double price) {
		commonSellByPosition(rtSymbol, price, 0);
	}

	@Override
	public void sellTdByPosition(String rtSymbol, double price) {
		commonSellByPosition(rtSymbol, price, 1);
	}

	@Override
	public void sellYdByPosition(String rtSymbol, double price) {
		commonSellByPosition(rtSymbol, price, -1);
	}

	@Override
	public void sellShortByPreset(String rtSymbol, double price) {
		ContractPositionDetail contractPositionDetail = contractPositionMap.get(rtSymbol);

		TradeContractSetting tradeContractSetting = strategySetting.getContract(rtSymbol);
		if (tradeContractSetting != null) {
			List<ContractTradeGatewaySetting> tradeGateways = tradeContractSetting.getTradeGateways();
			if (tradeGateways != null && !tradeGateways.isEmpty()) {

				if (contractPositionDetail != null) {
					int shortPos = contractPositionDetail.getShortPos();
					int fixedPos = tradeContractSetting.getTradeFixedPos();
					if (shortPos == fixedPos) {
						log.warn("合约{}的空头总持仓量已经达到预设值,指令终止！", rtSymbol);
						return;
					} else if (shortPos > fixedPos) {
						log.error("合约{}的空头总持仓量{}已经超过预设值{},指令终止！", rtSymbol);
						stopTrading(true);
						return;
					}
				}

				for (ContractTradeGatewaySetting tradeGteway : tradeGateways) {
					String gatewayID = tradeGteway.getGatewayID();
					int gatewayFixedPos = tradeGteway.getTradeFixedPos();
					int tradePos = gatewayFixedPos;
					if (gatewayFixedPos > 0) {
						PositionDetail positionDetail = contractPositionDetail.getPositionDetailMap().get(gatewayID);

						if (positionDetail != null) {
							int gatewayShortPos = positionDetail.getShortPos();
							int gatewayShortOpenFrozenPos = positionDetail.getShortOpenFrozen();
							if (gatewayShortPos + gatewayShortOpenFrozenPos == gatewayFixedPos) {
								log.warn("合约{}接口{}的空头持仓量加开仓冻结量已经达到预设值,指令忽略！", rtSymbol, gatewayID);
								continue;
							} else if (gatewayShortPos > gatewayFixedPos) {
								log.error("合约{}接口{}的空头持仓量{}加开仓冻结量{}已经超过预设值{},指令忽略！", rtSymbol, gatewayID,
										gatewayShortPos, gatewayShortOpenFrozenPos, gatewayFixedPos);
								stopTrading(true);
								continue;
							} else {
								tradePos = gatewayFixedPos - (gatewayShortPos + gatewayShortOpenFrozenPos);
							}
						}

						sellShort(rtSymbol, tradePos, price, gatewayID);
					} else {
						log.error("合约{}接口{}配置中的仓位大小不正确", rtSymbol, gatewayID);
						stopTrading(true);
					}
				}
			} else {
				log.error("未找到合约{}配置中的接口配置", rtSymbol);
				stopTrading(true);
			}
		} else {
			log.error("未找到合约{}的配置", rtSymbol);
			stopTrading(true);
		}

	}

	private void commonBuyToCoverByPosition(String rtSymbol, double price, int offsetType) {
		ContractPositionDetail contractPositionDetail = contractPositionMap.get(rtSymbol);
		if (contractPositionDetail != null) {
			int shortPos = contractPositionDetail.getShortPos();
			if (shortPos == 0) {
				log.warn("合约{}的空头总持仓量为0,指令终止！", rtSymbol);
				return;
			} else if (shortPos < 0) {
				log.error("合约{}的空头总持仓量{}小于0！", rtSymbol, shortPos);
				stopTrading(true);
				return;
			}

			for (Entry<String, PositionDetail> entry : contractPositionDetail.getPositionDetailMap().entrySet()) {
				String gatewayID = entry.getKey();
				PositionDetail positionDetail = entry.getValue();
				if (positionDetail == null) {
					continue;
				}

				if (positionDetail.getShortPos() > 0) {
					if (offsetType >= 0) {
						if (positionDetail.getShortOpenFrozen() > 0) {
							log.warn("合约{}接口{}空头开仓冻结为{}，这部分不会被处理", rtSymbol, gatewayID,
									positionDetail.getShortOpenFrozen());
						}

						if (positionDetail.getShortTd() > 0) {
							buyToCoverTd(rtSymbol, positionDetail.getShortTd(), price, gatewayID);
						}
					}
					if (offsetType <= 0) {
						if (positionDetail.getShortYd() > 0) {
							buyToCoverYd(rtSymbol, positionDetail.getShortYd(), price, gatewayID);
						}
					}

				} else {
					log.error("合约{}接口{}空头持仓大小不正确", rtSymbol, gatewayID);
					stopTrading(true);
				}
			}
		} else {
			log.error("未找到合约{}的持仓信息", rtSymbol);
		}
	}

	@Override
	public void buyToCoverByPosition(String rtSymbol, double price) {
		commonBuyToCoverByPosition(rtSymbol, price, 0);

	}

	@Override
	public void buyToCoverTdByPosition(String rtSymbol, double price) {
		commonBuyToCoverByPosition(rtSymbol, price, 1);

	}

	@Override
	public void buyToCoverYdByPosition(String rtSymbol, double price) {
		commonBuyToCoverByPosition(rtSymbol, price, -1);
	}

	@Override
	public void buyToLockByPosition(String rtSymbol, double price) {
		ContractPositionDetail contractPositionDetail = contractPositionMap.get(rtSymbol);
		if (contractPositionDetail != null) {
			int shortPos = contractPositionDetail.getShortPos();
			if (shortPos == 0) {
				log.warn("合约{}的空头总持仓量为0,指令终止！", rtSymbol);
				return;
			} else if (shortPos < 0) {
				log.error("合约{}的空头总持仓量{}小于0！", rtSymbol, shortPos);
				stopTrading(true);
				return;
			}

			for (Entry<String, PositionDetail> entry : contractPositionDetail.getPositionDetailMap().entrySet()) {
				String gatewayID = entry.getKey();
				PositionDetail positionDetail = entry.getValue();
				if (positionDetail == null) {
					continue;
				}
				if (positionDetail.getShortOpenFrozen() > 0) {
					log.warn("合约{}接口{}空头开仓冻结为{}，这部分不会被处理", rtSymbol, gatewayID, positionDetail.getShortOpenFrozen());
				}
				if (positionDetail.getShortPos() > 0) {
					buy(rtSymbol, positionDetail.getShortPos(), price, gatewayID);
				} else {
					log.error("合约{}接口{}空头持仓大小不正确", rtSymbol, gatewayID);
					stopTrading(true);
				}
			}
		} else {
			log.error("未找到合约{}的持仓信息", rtSymbol);
		}
	}

	@Override
	public void sellShortToLockByPosition(String rtSymbol, double price) {
		ContractPositionDetail contractPositionDetail = contractPositionMap.get(rtSymbol);

		if (contractPositionDetail != null) {
			int longPos = contractPositionDetail.getLongPos();
			if (longPos == 0) {
				log.warn("合约{}的多头总持仓量为0,指令终止！", rtSymbol);
				return;
			} else if (longPos < 0) {
				log.error("合约{}的多头总持仓量{}小于0！", rtSymbol, longPos);
				stopTrading(true);
				return;
			}

			for (Entry<String, PositionDetail> entry : contractPositionDetail.getPositionDetailMap().entrySet()) {
				String gatewayID = entry.getKey();
				PositionDetail positionDetail = entry.getValue();
				if (positionDetail == null) {

					continue;
				}

				if (positionDetail.getLongPos() > 0) {
					if (positionDetail.getLongOpenFrozen() > 0) {
						log.warn("合约{}接口{}多头开仓冻结为{}，这部分不会被处理", rtSymbol, gatewayID, positionDetail.getLongOpenFrozen());
					}
					sellShort(rtSymbol, positionDetail.getLongPos(), price, gatewayID);
				} else {
					log.error("合约{}接口{}多头持仓大小不正确", rtSymbol, gatewayID);
					stopTrading(true);
				}
			}
		} else {
			log.error("未找到合约{}的持仓信息", rtSymbol);
		}
	}

	@Override
	public void processTick(Tick tick) {
		try {
			// 处理停止单
			processStopOrder(tick);
			onTick(tick);
			// 基于合约的onBar和onMinBar
			String bgKey = tick.getRtSymbol();
			// 基于合约+接口的onBar和onMinBar，使用这个key会多次触发同一策略下同一品种的相同时间bar的事件
			// String bgKey = tick.getRtSymbol()+tick.getGatewayID();
			BarGenerator barGenerator;
			if (barGeneratorMap.containsKey(bgKey)) {
				barGenerator = barGeneratorMap.get(bgKey);
			} else {
				barGenerator = new BarGenerator(new CallBackXMinBar() {
					@Override
					public void call(Bar bar) {
						processBar(bar);
					}
				});
				barGeneratorMap.put(bgKey, barGenerator);
			}

			// 更新1分钟bar生成器
			barGenerator.updateTick(tick);
		} catch (Exception e) {
			stopTrading(true);
			log.error("{} 调用onTick发生异常,停止策略！！！", logStr, e);
		}
	}

	@Override
	public void processTrade(Trade trade) {
		try {
			// 过滤重复
			if (!rtTradeIDSet.contains(trade.getRtTradeID())) {
				ContractPositionDetail contractPositionDetail = contractPositionMap.get(trade.getRtSymbol());
				contractPositionDetail.updateTrade(trade);
				savePosition();
				rtTradeIDSet.add(trade.getRtTradeID());

				onTrade(trade);
			}

		} catch (Exception e) {
			stopTrading(true);
			log.error("{} 调用onTrade发生异常,停止策略！！！", logStr, e);
		}
	}

	@Override
	public void processOrder(Order order) {
		try {
			workingOrderMap.put(order.getRtOrderID(), order);
			if(RtConstant.STATUS_FINISHED.contains(order.getStatus())) {
				workingOrderMap.remove(order.getRtOrderID());
			}
			ContractPositionDetail contractPositionDetail = contractPositionMap.get(order.getRtSymbol());
			contractPositionDetail.updateOrder(order);
			onOrder(order);
		} catch (Exception e) {
			stopTrading(true);
			log.error("{} 调用onOrder发生异常,停止策略！！！", logStr, e);
		}
	}

	@Override
	public void processBar(Bar bar) {

		String bgKey = bar.getRtSymbol();
		// 调用onBar方法，此方法会在onTick->bg.updateTick执行之后再执行
		try {
			onBar(bar);
		} catch (Exception e) {
			stopTrading(true);
			log.error("{} 调用onBar发生异常,停止策略！！！", logStr, e);
		}
		// 判断是否需要调用xMinBarGenerate,设置xMin大于1分钟xMinBarGenerate会生效
		if (strategySetting.getxMin() > 1) {
			XMinBarGenerator xMinBarGenerator;
			if (xMinBarGeneratorMap.containsKey(bgKey)) {
				xMinBarGenerator = xMinBarGeneratorMap.get(bgKey);
			} else {
				xMinBarGenerator = new XMinBarGenerator(strategySetting.getxMin(), new CallBackXMinBar() {
					@Override
					public void call(Bar bar) {
						try {
							// 调用onXMinBar方法
							// 此方法会在onTick->bg.updateTick->onBar->xbg.updateBar执行之后再执行
							onXMinBar(bar);
						} catch (Exception e) {
							stopTrading(true);
							log.error("{} 调用onXMinBar发生异常,停止策略！！！", logStr, e);
						}
					}
				});
				xMinBarGeneratorMap.put(bgKey, xMinBarGenerator);
			}
			xMinBarGenerator.updateBar(bar);
		}
	}

	// ##############################################################################

	/**
	 * CallBack接口，用于注册Bar生成器回调事件
	 */
	public static interface CallBackXMinBar {
		void call(Bar bar);
	}

	/**
	 * 1分钟Bar生成器
	 */
	public static class BarGenerator {

		private Bar bar = null;
		private Tick lastTick = null;
		CallBackXMinBar callBackXMinBar;

		BarGenerator(CallBackXMinBar callBackXMinBar) {
			this.callBackXMinBar = callBackXMinBar;
		}

		/**
		 * 更新Tick数据
		 * 
		 * @param tick
		 */
		public void updateTick(Tick tick) {

			boolean newMinute = false;

			if (lastTick != null) {
				// 此处过滤用于一个策略在多个接口订阅了同一个合约的情况下,Tick到达顺序和实际产生顺序不一致或者重复的情况
				if (tick.getDateTime().getMillis() <= lastTick.getDateTime().getMillis()) {
					return;
				}
			}

			if (bar == null) {
				bar = new Bar();
				newMinute = true;
			} else if (bar.getDateTime().getMinuteOfDay() != tick.getDateTime().getMinuteOfDay()) {

				bar.setDateTime(bar.getDateTime().withSecondOfMinute(0).withMillisOfSecond(0));
				bar.setActionTime(bar.getDateTime().toString(RtConstant.T_FORMAT_WITH_MS_Formatter));

				// 回调OnBar方法
				callBackXMinBar.call(bar);

				bar = new Bar();
				newMinute = true;
			}

			if (newMinute) {
				bar.setGatewayID(tick.getGatewayID());
				bar.setExchange(tick.getExchange());
				bar.setRtSymbol(tick.getRtSymbol());
				bar.setSymbol(tick.getSymbol());

				bar.setTradingDay(tick.getTradingDay());
				;
				bar.setActionDay(tick.getActionDay());

				bar.setOpen(tick.getLastPrice());
				bar.setHigh(tick.getLastPrice());
				bar.setLow(tick.getLastPrice());

				bar.setDateTime(tick.getDateTime());
			} else {
				bar.setHigh(Math.max(bar.getHigh(), tick.getLastPrice()));
				bar.setLow(Math.min(bar.getLow(), tick.getLastPrice()));
			}

			bar.setClose(tick.getLastPrice());
			bar.setOpenInterest(tick.getOpenInterest());
			if (lastTick != null) {
				bar.setVolume(bar.getVolume() + (tick.getVolume() - lastTick.getVolume()));
			}

			lastTick = tick;
		}
	}

	/**
	 * X分钟Bar生成器,xMin在策略初始化时指定,当值大于1小于时生效。建议此数值不要大于120
	 */
	public static class XMinBarGenerator {

		private int xMin;
		private Bar xMinBar = null;
		CallBackXMinBar callBackXMinBar;

		XMinBarGenerator(int xMin, CallBackXMinBar callBackXMinBar) {
			this.callBackXMinBar = callBackXMinBar;
			this.xMin = xMin;
		}

		public void updateBar(Bar bar) {

			if (xMinBar == null) {
				xMinBar = new Bar();
				xMinBar.setGatewayID(bar.getGatewayID());
				xMinBar.setExchange(bar.getExchange());
				xMinBar.setRtSymbol(bar.getRtSymbol());
				xMinBar.setSymbol(bar.getSymbol());

				xMinBar.setTradingDay(bar.getTradingDay());
				xMinBar.setActionDay(bar.getActionDay());

				xMinBar.setOpen(bar.getOpen());
				xMinBar.setHigh(bar.getHigh());
				xMinBar.setLow(bar.getLow());

				xMinBar.setDateTime(bar.getDateTime());

			} else {
				xMinBar.setHigh(Math.max(xMinBar.getHigh(), bar.getHigh()));
				xMinBar.setLow(Math.min(xMinBar.getLow(), bar.getLow()));
			}

			if ((xMinBar.getDateTime().getMinuteOfDay() + 1) % xMin == 0) {
				bar.setDateTime(bar.getDateTime().withSecondOfMinute(0).withMillisOfSecond(0));
				bar.setActionTime(bar.getDateTime().toString(RtConstant.T_FORMAT_WITH_MS_Formatter));

				// 回调onXMinBar方法
				callBackXMinBar.call(xMinBar);

				xMinBar = null;
			}

		}
	}

}
