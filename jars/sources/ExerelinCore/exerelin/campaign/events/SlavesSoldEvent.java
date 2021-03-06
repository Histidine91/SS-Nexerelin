package exerelin.campaign.events;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.log4j.Logger;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseOnMessageDeliveryScript;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.comm.CommMessageAPI;
import com.fs.starfarer.api.campaign.comm.MessagePriority;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.events.CampaignEventPlugin;
import com.fs.starfarer.api.campaign.events.CampaignEventTarget;
import com.fs.starfarer.api.impl.campaign.events.BaseEventPlugin;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import exerelin.utilities.NexConfig;
import exerelin.utilities.NexFactionConfig;
import exerelin.utilities.NexFactionConfig.Morality;
import exerelin.utilities.NexUtilsReputation;
import exerelin.utilities.StringHelper;


public class SlavesSoldEvent extends BaseEventPlugin {

	public static Logger log = Global.getLogger(SlavesSoldEvent.class);
	
	protected float avgRepChange = 0;
	protected List<String> factionsToNotify = new ArrayList<>();
	protected Map<String, Object> params = new HashMap<>();
	protected Map<String, Float> repPenalties = new HashMap<>();
	protected int numSlaves = 0;
		
	@Override
	public void init(String type, CampaignEventTarget eventTarget) {
		super.init(type, eventTarget);
	}
	
	@Override
	public void setParam(Object param) {
		params = (Map<String, Object>)param;
		factionsToNotify = (List<String>)params.get("factionsToNotify");
		repPenalties = (Map<String, Float>)params.get("repPenalties");
		numSlaves = (Integer)params.get("numSlaves");
		avgRepChange = (Float)params.get("avgRepChange");
	}
	
	@Override
	public void startEvent() {
		
	}
	
	public void reportSlaveTrade(MarketAPI loc, Map<String, Object> params) {
		market = loc;
		setParam(params);
		MessagePriority priority = MessagePriority.ENSURE_DELIVERY;
		Global.getSector().reportEventStage(this, "report", loc.getPrimaryEntity(), priority, new BaseOnMessageDeliveryScript() {
			public void beforeDelivery(CommMessageAPI message) {
					for (String factionId : factionsToNotify)
					{
						float penalty = repPenalties.get(factionId);
						if (factionId.equals(market.getFactionId()))
						{
							NexUtilsReputation.adjustPlayerReputation(Global.getSector().getFaction(factionId), 
									market.getPrimaryEntity().getActivePerson(), penalty, penalty * 1.5f, null, null);
						}
						else NexUtilsReputation.adjustPlayerReputation(Global.getSector().getFaction(factionId), penalty);
					}
			}
		});
	}

	@Override
	public String getEventName() {
		return StringHelper.getStringAndSubstituteToken("exerelin_events", "slaveTrade", 
				"$market", market.getName());
	}
	
	/*
	@Override
	public String getCurrentImage() {
		return newOwner.getLogo();
	}

	@Override
	public String getCurrentMessageIcon() {
		return newOwner.getLogo();
	}
	*/
		
	@Override
	public CampaignEventPlugin.CampaignEventCategory getEventCategory() {
		return CampaignEventPlugin.CampaignEventCategory.DO_NOT_SHOW_IN_MESSAGE_FILTER;
	}
	
	
	@Override
	public Map<String, String> getTokenReplacements() {
		Map<String, String> map = super.getTokenReplacements();
		map.put("$market", market.getName());
		map.put("$location", market.getPrimaryEntity().getContainingLocation().getName());
		map.put("$numFactions", "" + factionsToNotify.size());
		map.put("$repPenaltyAbs", "" + (int)Math.ceil(Math.abs(avgRepChange*100f)));
		if (market.getPrimaryEntity().hasTag(Tags.STATION)) {
			map.put("$onOrAt", StringHelper.getString("at"));
		} else {
			map.put("$onOrAt", StringHelper.getString("on"));
		}
		return map;
	}
	
	@Override
	public String[] getHighlights(String stageId) {
		List<String> result = new ArrayList<>();
		addTokensToList(result, "$numFactions");
		addTokensToList(result, "$repPenaltyAbs");
		return result.toArray(new String[0]);
	}
	
	@Override
	public boolean isDone() {
		return false;
	}

	@Override
	public boolean allowMultipleOngoingForSameTarget() {
		return true;
	}
	
	@Override
	public boolean showAllMessagesIfOngoing() {
		return false;
	}
	
	public static float getSlaveRepPenalty(String factionId, int slaveCount)
	{
		FactionAPI faction = Global.getSector().getFaction(factionId);
		if (faction.isNeutralFaction() || faction.isPlayerFaction() || (factionId.equals(Factions.PLAYER)))
			return 0;
		
		float penalty = 0;
		NexFactionConfig conf = NexConfig.getFactionConfig(factionId);
		if (conf.morality == Morality.NEUTRAL) penalty = NexConfig.prisonerSlaveRepValue;
		else if (conf.morality == Morality.GOOD) penalty = NexConfig.prisonerSlaveRepValue * 2;
		else return 0;
		
		return penalty * slaveCount;
	}
}