package exerelin.campaign.intel.groundbattle;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import org.apache.log4j.Logger;

public class GBUtils {
	
	public static Logger log = Global.getLogger(GBUtils.class);
	
	/**
	 * Estimates the strength of the militia, marines and heavy units in the planetary garrison.
	 * @param intel
	 * @param useHealth If true, will take into account garrison damage from recent invasions.
	 * @return
	 */
	public static float[] estimateDefenderStrength(GroundBattleIntel intel, boolean useHealth) {
		float militia = 1, marines = 0, heavies = 0;
		if (intel.market.getSize() >= 5) {
			militia = 0.75f;
			marines = 0.25f;
		}
		if (intel.market.getMemoryWithoutUpdate().getBoolean(MemFlags.MARKET_MILITARY)) {
			militia -= 0.25f;
			marines += 0.25f;
		}
			
		for (IndustryForBattle ind : intel.industries) {
			militia += ind.getPlugin().getTroopContribution("militia");
			marines += ind.getPlugin().getTroopContribution("marine");
			heavies += ind.getPlugin().getTroopContribution("heavy");
		}
		
		float countForSize = getTroopCountForMarketSize(intel.getMarket());
		countForSize *= 0.5f + (intel.market.getStabilityValue() / 10f) * 0.75f;
		
		float health = 1;
		if (useHealth) {
			health = 1 - GBUtils.getGarrisonDamageMemory(intel.getMarket());
		}
		
		militia = Math.round(militia * countForSize * 2.5f * health);
		marines = Math.round(marines * countForSize * health);
		heavies = Math.round(heavies * countForSize / GroundUnit.HEAVY_COUNT_DIVISOR * health);
		
		return new float[] {militia * GroundUnit.ForceType.MILITIA.strength,
				marines * GroundUnit.ForceType.MARINE.strength,
				heavies * GroundUnit.ForceType.HEAVY.strength};
	}
	
	public static float estimateTotalDefenderStrength(GroundBattleIntel intel, boolean useHealth) 
	{
		float str = 0;
		float[] strByType = estimateDefenderStrength(intel, useHealth);
		for (float thisStr: strByType) {
			str += thisStr;
		}
		return str;
	}
	
	public static float[] estimatePlayerStrength() {
		CargoAPI cargo = Global.getSector().getPlayerFleet().getCargo();
		int marines = cargo.getMarines();
		int heavyArms = (int)cargo.getCommodityQuantity(Commodities.HAND_WEAPONS);
		heavyArms = Math.min(heavyArms, marines/GroundUnit.CREW_PER_MECH);
		
		int remainingMarines = marines - heavyArms * GroundUnit.CREW_PER_MECH;
		
		return new float[] {remainingMarines * GroundUnit.ForceType.MARINE.strength,
				heavyArms * GroundUnit.ForceType.HEAVY.strength};
	}
	
	public static float getTroopCountForMarketSize(MarketAPI market) {
		int size = market.getSize();
		float mult = (float)Math.pow(2, size - 1);
		
		return Math.round(mult * GBConstants.BASE_GARRISON_SIZE);
	}
	
	public static float getGarrisonDamageMemory(MarketAPI market) {
		if (!market.getMemoryWithoutUpdate().contains(GBConstants.MEMKEY_GARRISON_DAMAGE)) 
			return 0;
		float damage = market.getMemoryWithoutUpdate().getFloat(GBConstants.MEMKEY_GARRISON_DAMAGE);
		return damage;
	}
	
	public static void setGarrisonDamageMemory(MarketAPI market, float damage) {
		if (damage <= 0) {
			market.getMemoryWithoutUpdate().unset(GBConstants.MEMKEY_GARRISON_DAMAGE);
			log.info("Unsetting garrison damage for " + market.getName());
		}			
		else {
			market.getMemoryWithoutUpdate().set(GBConstants.MEMKEY_GARRISON_DAMAGE, damage);
			log.info("Setting garrison damage for " + market.getName() + ": " + String.format("%.3f", damage));
		}
			
	}
}