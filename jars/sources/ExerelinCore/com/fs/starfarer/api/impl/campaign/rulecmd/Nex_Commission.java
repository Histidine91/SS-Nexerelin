package com.fs.starfarer.api.impl.campaign.rulecmd;

import com.fs.starfarer.api.campaign.RepLevel;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin;
import com.fs.starfarer.api.impl.campaign.ids.Ranks;
import com.fs.starfarer.api.impl.campaign.rulecmd.missions.Commission;
import static com.fs.starfarer.api.impl.campaign.rulecmd.missions.Commission.COMMISSION_REQ;
import exerelin.utilities.ExerelinConfig;
import exerelin.utilities.ExerelinFactionConfig;

public class Nex_Commission extends Commission {
	
	// replace the "faction issues commissions" check with "playable faction" check
	@Override
	protected boolean personCanGiveCommission() {
		if (person == null) return false;
		if (person.getFaction().isPlayerFaction()) return false;
		
		ExerelinFactionConfig conf = ExerelinConfig.getExerelinFactionConfig(person.getFaction().getId());
		if (!conf.playableFaction) return false;
		
		//if (Misc.getCommissionFactionId() != null) return false;
		
		return Ranks.POST_BASE_COMMANDER.equals(person.getPostId()) ||
			   Ranks.POST_STATION_COMMANDER.equals(person.getPostId()) ||
			   Ranks.POST_ADMINISTRATOR.equals(person.getPostId()) ||
			   Ranks.POST_OUTPOST_COMMANDER.equals(person.getPostId());
	}
	
	@Override
	protected boolean playerMeetsCriteria() {
		ExerelinFactionConfig conf = ExerelinConfig.getExerelinFactionConfig(person.getFaction().getId());
		if (conf.pirateFaction)
			return faction.getRelToPlayer().isAtWorst(RepLevel.SUSPICIOUS);
		
		return super.playerMeetsCriteria();
	}
	
	@Override
	protected void printRequirements() {
		RepLevel required = COMMISSION_REQ;
		ExerelinFactionConfig conf = ExerelinConfig.getExerelinFactionConfig(person.getFaction().getId());
		if (conf.pirateFaction)
			required = RepLevel.SUSPICIOUS;
		
		CoreReputationPlugin.addRequiredStanding(entityFaction, required, null, dialog.getTextPanel(), null, null, 0f, true);
		CoreReputationPlugin.addCurrentStanding(entityFaction, null, dialog.getTextPanel(), null, null, 0f);
	}
}