package exerelin.campaign.intel.agents;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CustomCampaignEntityAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.FactionAPI.ShipPickMode;
import com.fs.starfarer.api.campaign.FactionProductionAPI;
import com.fs.starfarer.api.campaign.FleetInflater;
import com.fs.starfarer.api.campaign.RepLevel;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.campaign.DerelictShipEntityPlugin;
import com.fs.starfarer.api.impl.campaign.fleets.DefaultFleetInflaterParams;
import com.fs.starfarer.api.impl.campaign.ids.Entities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import com.fs.starfarer.api.impl.campaign.procgen.themes.BaseThemeGenerator;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.ShipRecoverySpecial;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.ShipRecoverySpecial.ShipRecoverySpecialData;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import static com.fs.starfarer.api.util.Misc.random;
import com.fs.starfarer.api.util.Pair;
import exerelin.campaign.AllianceManager;
import exerelin.campaign.CovertOpsManager;
import exerelin.campaign.ExerelinReputationAdjustmentResult;
import exerelin.campaign.submarkets.PrismMarket;
import exerelin.utilities.NexConfig;
import exerelin.utilities.NexUtils;
import exerelin.utilities.NexUtilsAstro;
import exerelin.utilities.NexUtilsFaction;
import exerelin.utilities.StringHelper;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.lazywizard.lazylib.MathUtils;

/**
 * Obtains semi-illegally a ship from the target faction and sends it to a specified location.
 */
public class ProcureShip extends CovertActionIntel {
	
	public static final float FAILURE_REFUND_MULT = 0.75f;
	public static final String BUTTON_CHANGE_DESTINATION = "changeDestination";
	public static final List<String> ALLOWED_SUBMARKETS = new ArrayList<>(Arrays.asList(new String[] {
		Submarkets.SUBMARKET_OPEN, Submarkets.SUBMARKET_BLACK, 
		Submarkets.GENERIC_MILITARY, "AL_militaryMarket"
	}));
	
	protected FleetMemberAPI ship;
	protected Float timeToDeliver = null;
	protected MarketAPI destination;
	protected boolean delivered;
	
	public ProcureShip(AgentIntel agentIntel, MarketAPI market, FleetMemberAPI ship, 
			FactionAPI agentFaction, FactionAPI targetFaction, 
			boolean playerInvolved, Map<String, Object> params) {
		super(agentIntel, market, agentFaction, targetFaction, playerInvolved, params);
		this.ship = ship;
		destination = pickDestination();
	}
	
	/**
	 * Picks an appropriate destination market to send the ship: first the player's 
	 * current gathering point, then the nearest market where player has storage access, 
	 * then the market where the agent action is taking place.
	 * @return
	 */
	public MarketAPI pickDestination() {
		FactionAPI pf = Global.getSector().getPlayerFaction();
		FactionProductionAPI prod = pf.getProduction();
		
		MarketAPI gatheringPoint = prod.getGatheringPoint();
		if (gatheringPoint != null) return gatheringPoint;
		
		MarketAPI nearest = null;
		float nearestDistSq = Integer.MAX_VALUE;
		for (MarketAPI dest : Global.getSector().getEconomy().getMarketsCopy())
		{
			if (dest.getFaction().isHostileTo(Factions.PLAYER))
				continue;
			if (!Misc.playerHasStorageAccess(dest))
				continue;
			float distSq = MathUtils.getDistanceSquared(dest.getLocationInHyperspace(), 
					market.getLocationInHyperspace());
			if (distSq < nearestDistSq) {
				nearestDistSq = distSq;
				nearest = dest;
			}
		}
		if (nearest != null) return nearest;
		return market;
	}
	
	public void setDestination(MarketAPI market) {
		destination = market;
	}
	
	public MarketAPI getDestination() {
		return destination;
	}
	
	public int getMemberSizeMult() {
		switch (ship.getHullSpec().getHullSize()) {
			case CAPITAL_SHIP:
				return 5;
			case CRUISER:
				return 3;
			case DESTROYER:
				return 2;
			default:
				return 1;
		}
	}
	
	/**
	 * Should match {@code getRequiredLevelAssumingLegal} in MilitarySubmarketPlugin.java.
	 * @param spec
	 * @return
	 */
	public RepLevel getRequiredLevelForLegal(ShipHullSpecAPI spec) {
		int fp = spec.getFleetPoints();
		HullSize size = spec.getHullSize();

		if (size == HullSize.CAPITAL_SHIP || fp > 15) return RepLevel.COOPERATIVE;
		if (size == HullSize.CRUISER || fp > 10) return RepLevel.FRIENDLY;
		if (size == HullSize.DESTROYER || fp > 5) return RepLevel.WELCOMING;
		return RepLevel.FAVORABLE;
	}
	
	protected boolean hasCommission() {
		if (!targetFaction.getCustomBoolean(Factions.CUSTOM_OFFERS_COMMISSIONS)) return true;
		
		String cfId = NexUtilsFaction.getCommissionFactionId();
		String afId = agentFaction.getId();
		String tgtId = targetFaction.getId();
		Global.getLogger(this.getClass()).info(cfId + ", " + afId + ", " + tgtId);
		
		if (AllianceManager.areFactionsAllied(afId, tgtId)) return true;
		if (cfId != null) {
			if (AllianceManager.areFactionsAllied(cfId, tgtId)) return true;
		}
		
		return false;
	}
	
	public boolean isLegal() {
		if (ship == null) return false;
		return isLegal(ship.getHullSpec());
	}
	
	public boolean isLegal(ShipHullSpecAPI spec) {
		if (spec.getHints().contains(ShipTypeHints.CIVILIAN)) return true;
		if (!targetFaction.getRelationshipLevel(agentFaction).isAtWorst(getRequiredLevelForLegal(spec)))
			return false;
		
		return hasCommission();
	}
	
	public void setShip(FleetMemberAPI ship) {
		this.ship = ship;
	}
	
	/**
	 * Applies a fleet inflater to the ship to be delivered.
	 * Mostly copied from CoreScript's {@code doCustomProduction()}.
	 */
	public void prepShipForDelivery() {
		if (ship.isFighterWing()) return;
		
		CampaignFleetAPI tempFleet = Global.getFactory().createEmptyFleet(Factions.PLAYER, "temp", true);
		tempFleet.setCommander(Global.getSector().getPlayerPerson());
		DefaultFleetInflaterParams p = new DefaultFleetInflaterParams();
		p.quality = market.getShipQualityFactor();
		p.mode = ShipPickMode.PRIORITY_THEN_ALL;
		p.persistent = false;
		p.seed = random.nextLong();
		p.timestamp = null;
		
		FleetInflater inflater = Misc.getInflater(tempFleet, p);
		tempFleet.setInflater(inflater);
		
		tempFleet.getFleetData().addFleetMember(getRandomVariantId());
		tempFleet.inflateIfNeeded();
		ship.setShipName(targetFaction.pickRandomShipName());
	}
	
	public String getRandomVariantId() {
		List<String> variants = Global.getSettings().getHullIdToVariantListMap().get(ship.getHullId());
		String variantId = NexUtils.getRandomListElement(variants);
		if (variantId == null) variantId = ship.getHullId() + "_Hull";
		return variantId;
	}
	
	/**
	 * Create a derelict of the purchased ship around the destination market, if it's decivilized
	 * when the delivery is made.
	 */
	protected void createDerelict() {
		if (ship.isFighterWing()) {	// TODO
			return;
		}
		
		SectorEntityToken toOrbit = destination.getPrimaryEntity();
		String variantId = getRandomVariantId();
		DerelictShipEntityPlugin.DerelictShipData params = new DerelictShipEntityPlugin.DerelictShipData(
				new ShipRecoverySpecial.PerShipData(variantId, ShipRecoverySpecial.ShipCondition.GOOD), false);
		params.ship.shipName = ship.getShipName();
		
		ShipRecoverySpecialData data = new ShipRecoverySpecialData(null);
		data.addShip(params.ship);

		CustomCampaignEntityAPI derelict = (CustomCampaignEntityAPI) BaseThemeGenerator.addSalvageEntity(
										 toOrbit.getContainingLocation(),
										 Entities.WRECK, Factions.NEUTRAL, params);
		Misc.setSalvageSpecial(derelict, data);

		// orbit
		float orbitRadius = toOrbit.getRadius() + 100;
		float orbitPeriod = NexUtilsAstro.getOrbitalPeriod(toOrbit, orbitRadius);
		derelict.setCircularOrbitWithSpin(toOrbit, NexUtilsAstro.getRandomAngle(random), orbitRadius, orbitPeriod, 20, 30);
	}
	
	protected void deliver() {
		delivered = true;
		if (destination.getSubmarket(Submarkets.SUBMARKET_STORAGE) != null) {
			if (ship.isFighterWing())
				destination.getSubmarket(Submarkets.SUBMARKET_STORAGE).getCargo().addFighters(ship.getHullId(), 1);
			else
				destination.getSubmarket(Submarkets.SUBMARKET_STORAGE).getCargo().getMothballedShips().addFleetMember(ship);
		} 
		// if no storage, add as derelict around market
		else if (destination.getPrimaryEntity() != null) 
		{
			createDerelict();
		}
		endAfterDelay();
		this.sendUpdateIfPlayerHasIntel(null, false, false);
	}
	
	@Override
	public MutableStat getCostStat() {
		MutableStat cost = new MutableStat(0);
		if (ship == null) return cost;
		
		cost.modifyFlat("base", ship.getBaseBuyValue(), getString("costShipBase", true));
		
		if (!isLegal()) {
			cost.modifyMult("generalMult", getDef().baseCost, getString("costShipGeneralMult", true));
		}
		else {
			cost.modifyMult("tariff", 1 + market.getTariff().getModifiedValue(), 
					getString("costShipGeneralMultLegal", true));
			cost.modifyMult("serviceFee", 1 + getDef().baseCost * 0.1f, 
					getString("costShipGeneralMultLegal2", true));
		}
		
		cost.modifyMult("hullMult", CovertOpsManager.getStealShipCostMult(ship.getHullId()), 
				StringHelper.getString("hull", true) + ": " + ship.getHullSpec().getHullName());
		
		return cost;
	}
	
	@Override
	public float getTimeNeeded() {
		float time = super.getTimeNeeded();
		if (isLegal()) time /= 2;
		return time;
	}
	
	// lower success rates for better ships
	@Override
	protected MutableStat getSuccessChance(boolean checkSP) {
		if (isLegal()) {
			MutableStat stat = new MutableStat(0);
			stat.modifyFlat("base", 100, getString("procureShipStatChanceLegal"));
			return stat;
		}
		
		MutableStat stat = super.getSuccessChance(checkSP);
		if (ship == null || (checkSP && sp.preventFailure())) return stat;
		
		float mult = 1;
		switch (ship.getHullSpec().getHullSize()) {
			case CAPITAL_SHIP:
				mult = 0.7f;
				break;
			case CRUISER:
				mult = 0.8f;
				break;
			case DESTROYER:
				mult = 0.9f;
				break;
		}
		
		stat.modifyMult("size", mult, getString("procureShipStatChanceMult"));
		return stat;
	}
	
	@Override
	protected MutableStat getDetectionChance(boolean fail) {
		if (isLegal()) return new MutableStat(0);
		return super.getDetectionChance(fail);
	}
	
	@Override
	public float getAlertLevelIncrease() {
		if (isLegal()) return 0;
		return getDef().alertLevelIncrease * getMemberSizeMult();
	}
	
	@Override
	protected float getXPMult() {
		if (isLegal()) {
			return super.getXPMult() * 0.5f;
		}
		return super.getXPMult() * getMemberSizeMult();
	}
	
	// Increases rep effects for better ships
	@Override
	protected ExerelinReputationAdjustmentResult adjustRelationsFromDetection(FactionAPI faction1, 
			FactionAPI faction2, RepLevel ensureAtBest, RepLevel ensureAtWorst, RepLevel limit, boolean useNPCMult)
	{
		int mult = getMemberSizeMult();
		float effectMin = -getDef().repLossOnDetect.two * mult;
		float effectMax = -getDef().repLossOnDetect.one * mult;
		return adjustRelations(faction1, faction2, effectMin, effectMax, ensureAtBest, ensureAtWorst, limit, useNPCMult);
	}

	@Override
	protected void onSuccess() {
		timeToDeliver = (float)getMemberSizeMult();	// TODO: base on distance instead? (maybe too fussy)
		if (destination == null)
			destination = pickDestination();
		prepShipForDelivery();
		
		adjustRepIfDetected(RepLevel.FAVORABLE, null);
		reportEvent();
	}
	
	@Override
	public int getAbortRefund() {
		// time-based refund only applies to the 25% "deposit", all of the rest is given back
		float timeRefund = super.getAbortRefund() * (1 - FAILURE_REFUND_MULT);
		return Math.round(timeRefund + (cost * FAILURE_REFUND_MULT));
	}

	@Override
	protected void onFailure() {
		Global.getSector().getPlayerFleet().getCargo().getCredits().add(cost * FAILURE_REFUND_MULT);
		adjustRepIfDetected(RepLevel.FAVORABLE, RepLevel.HOSTILE);
		reportEvent();
		endAfterDelay();
	}
	
	// Don't end after delay, event will only end when ship is delivered
	@Override
	protected void reportEvent() {
		timestamp = Global.getSector().getClock().getTimestamp();
		if (shouldReportEvent()) {
			Global.getSector().getIntelManager().addIntel(this);
		}
	}
	
	@Override
	public void advanceImpl(float amount) {
		super.advanceImpl(amount);
		
		// update destination if current one gets decivilized
		// but not if shipment is already underway
		if (result == null && !destination.isInEconomy())
			destination = pickDestination();
		
		if (timeToDeliver != null) {
			timeToDeliver -= Misc.getDays(amount);
			if (timeToDeliver <= 0) {
				deliver();
			}
		}
	}
	
	@Override
	public void addBulletPoints(TooltipMakerAPI info, Color color, float initPad, float pad) {
		super.addBulletPoints(info, color, initPad, pad);
		if (result != null && result.isSuccessful()) {
			String destName = destination.getName();
			
			if (delivered) {
				info.addPara(getString("shipDeliveryInfoCompleteShort"), pad, color,
						destination.getTextColorForFactionOrPlanet(), destName);
				return;
			}
			
			String days = Math.round(timeToDeliver) + "";	//Misc.getStringForDays(Math.round(timeToDeliver));
			String str = getString("shipDeliveryInfoShort");
			str = StringHelper.substituteToken(str, "$market", destName);
			str = StringHelper.substituteToken(str, "$time", days);
			
			LabelAPI label = info.addPara(str, pad, color);
			label.setHighlight(destName, days);
			label.setHighlightColors(destination.getTextColorForFactionOrPlanet(), Misc.getHighlightColor());
		}
	}
	
	@Override
	public void addResultPara(TooltipMakerAPI info, float pad) {	
		if (result != null && result.isSuccessful()) {
			String name = ship.getShipName();
			String hullName = ship.getHullSpec().getNameWithDesignationWithDashClass();
			String destName = destination.getName();
			Color hl = Misc.getHighlightColor();
			
			String str = getString(delivered ? "shipDeliveryInfoComplete" : "shipDeliveryInfo");
			str = StringHelper.substituteToken(str, "$shipName", name);
			str = StringHelper.substituteToken(str, "$shipClass", hullName);
			str = StringHelper.substituteToken(str, "$market", destName);
			
			if (delivered) {	// <ship> has arrived at <destination>
				LabelAPI label = info.addPara(str, pad);
				label.setHighlight(hullName, destName);
				label.setHighlightColors(hl, destination.getTextColorForFactionOrPlanet());
			} else {			// <ship> arriving at <destination> in <days>
				String days = Math.round(timeToDeliver) + "";	// Misc.getStringForDays(Math.round(timeToDeliver));
				str = StringHelper.substituteToken(str, "$time", days);
				LabelAPI label = info.addPara(str, pad);
				label.setHighlight(hullName, destName, days);
				label.setHighlightColors(hl, destination.getTextColorForFactionOrPlanet(), hl);
			}
			
			// show the ship
			List<FleetMemberAPI> ships = new ArrayList<>();
			ships.add(ship);
			info.addShipList(1, 1, 128, Color.WHITE, ships, pad);
		}
		super.addResultPara(info, pad);
	}

	@Override
	public void addCurrentActionPara(TooltipMakerAPI info, float pad) {
		String action = getActionString("intelStatus_procureShip", false);
		String name = ship.getHullSpec().getNameWithDesignationWithDashClass();
		String destName = destination.getName();
		action = StringHelper.substituteToken(action, "$ship", name);
		action = StringHelper.substituteToken(action, "$market", destName);
		
		LabelAPI label = info.addPara(action, pad);
		label.setHighlight(name, destName);
		label.setHighlightColors(Misc.getHighlightColor(), destination.getTextColorForFactionOrPlanet());
	}
	
	@Override
	public void addCurrentActionBullet(TooltipMakerAPI info, Color color, float pad) {
		String action = getActionString("intelStatus_procureShip", true);
		String name = ship.getHullSpec().getHullName();
		String destName = destination.getName();
		action = StringHelper.substituteToken(action, "$ship", name);
		action = StringHelper.substituteToken(action, "$market", destName);
		info.addPara(action, pad, color, Misc.getHighlightColor(), 
				name, destName, Math.round(daysRemaining) + "");
	}
	
	@Override
	protected List<Pair<String, String>> getStandardReplacements() {
		List<Pair<String, String>> sub = super.getStandardReplacements();
		sub.add(new Pair<>("$ship", ship.getHullSpec().getNameWithDesignationWithDashClass()));
		
		return sub;
	}
	
	@Override
	public String getDefId() {
		return "procureShip";
	}
	
	@Override
	public String getIcon() {
		return ship.getHullSpec().getSpriteName();
	}
	
	@Override
	public SectorEntityToken getMapLocation(SectorMapAPI map) {
		if (delivered)
			return destination.getPrimaryEntity();
		return super.getMapLocation(map);
	}
	
	@Override
	public List<ArrowData> getArrowData(SectorMapAPI map) {
		if (market != null && destination != null)
		{			
			List<ArrowData> result = new ArrayList<ArrowData>();
			ArrowData arrow = new ArrowData(market.getPrimaryEntity(), destination.getPrimaryEntity());
			arrow.color = Global.getSector().getPlayerFaction().getColor();
			arrow.width = 10f;
			result.add(arrow);
			
			return result;
		}
		
		return null;
	}
	
	public static List<FleetMemberAPI> getEligibleTargets(MarketAPI market, ProcureShip action) 
	{
		List<FleetMemberAPI> targets = new ArrayList<>();
		Set<String> hullsToCheck = new HashSet<>();
		
		boolean allShips = NexConfig.agentStealAllShips;
		if (allShips) {
			hullsToCheck.addAll(market.getFaction().getKnownShips());
		}
		else {
			for (SubmarketAPI submarket : market.getSubmarketsCopy()) {
				if (!ALLOWED_SUBMARKETS.contains(submarket.getSpecId()))
					continue;
				
				Global.getLogger(ProcureShip.class).info("Checking submarket " + submarket.getSpecId());
				submarket.getPlugin().updateCargoPrePlayerInteraction();	// make them refresh their cargo if needed
				
				for (FleetMemberAPI member : submarket.getCargo().getMothballedShips().getMembersListCopy()) 
				{
					String hullId = member.getHullSpec().getDParentHullId();
					if (hullId == null) hullId = member.getHullSpec().getHullId();
					Global.getLogger(ProcureShip.class).info(" Adding ship " + hullId);
					hullsToCheck.add(hullId);
				}
			}
		}
		
		for (String hullId : hullsToCheck) {
			ShipHullSpecAPI spec = Global.getSettings().getHullSpec(hullId);
			if (spec.getHints().contains(ShipTypeHints.UNBOARDABLE))
				continue;
			if (PrismMarket.getRestrictedShips().contains(hullId))
				continue;
			if (CovertOpsManager.getStealShipCostMult(hullId) <= 0)
				continue;
			if (action.agent != null && !action.isLegal(spec) && !action.agent.canStealShip())
				continue;

			List<String> variants = Global.getSettings().getHullIdToVariantListMap().get(hullId);
			String variantId = NexUtils.getRandomListElement(variants);
			if (variantId == null) variantId = hullId + "_Hull";
			FleetMemberAPI member = Global.getFactory().createFleetMember(FleetMemberType.SHIP, variantId);
			targets.add(member);
		}
		
		return targets;
	}
}
