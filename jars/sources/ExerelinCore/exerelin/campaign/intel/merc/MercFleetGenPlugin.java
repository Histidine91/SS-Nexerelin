package exerelin.campaign.intel.merc;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI.ShipPickMode;
import com.fs.starfarer.api.campaign.FactionDoctrineAPI;
import com.fs.starfarer.api.campaign.FleetInflater;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI.SkillLevelAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.events.OfficerManagerEvent;
import com.fs.starfarer.api.impl.campaign.events.OfficerManagerEvent.SkillPickPreference;
import com.fs.starfarer.api.impl.campaign.fleets.DefaultFleetInflaterParams;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.api.impl.campaign.ids.Ranks;
import com.fs.starfarer.api.plugins.OfficerLevelupPlugin;
import com.fs.starfarer.api.util.Misc;
import exerelin.campaign.intel.merc.MercDataManager.MercCompanyDef;
import exerelin.campaign.intel.merc.MercDataManager.OfficerDef;
import exerelin.utilities.NexUtils;
import java.util.List;
import java.util.Random;
import org.apache.log4j.Logger;

public class MercFleetGenPlugin {
	
	public static Logger log = Global.getLogger(MercFleetGenPlugin.class);
	
	protected MercContractIntel intel;
	
	public CampaignFleetAPI generateFleet(MarketAPI market) {
		MercCompanyDef def = intel.getDef();
		CampaignFleetAPI fleet = FleetFactoryV3.createEmptyFleet(def.factionIdForShipPick, FleetTypes.PATROL_MEDIUM, 
				market);
		
		boolean haveFlagship = false;
		for (int i=0; i<def.ships.size(); i++) {
			String variantId = def.getRandomShip(i);
			FleetMemberAPI member = fleet.getFleetData().addFleetMember(variantId);
			if (!haveFlagship) {
				haveFlagship = true;
				//member.setFlagship(true);
			}
		}
		if (def.extraFP > 0) {
			FleetParamsV3 params = new FleetParamsV3(
					market, FleetTypes.PATROL_MEDIUM,
					def.extraFP,	// combat
					0, 0, 0, 0, 0, // freighter, tanker, transport, liner, utility
					0	// qualityMod
			);
			params.factionId = def.factionIdForShipPick;
			params.mode = ShipPickMode.PRIORITY_THEN_ALL;
			params.officerNumberMult = 0;
			if (def.doctrineSizeOverride != null) {
				FactionDoctrineAPI copy = def.getFaction().getDoctrine().clone();
				copy.setShipSize(def.doctrineSizeOverride);
				params.doctrineOverride = copy;
			}
			Integer maxSize = (Integer)def.miscData.get("maxAutogenFleetSize");
			if (maxSize != null) {
				log.info(String.format("Setting max autogen fleet size for %s to %s", def.id, maxSize));
				params.maxNumShips = maxSize;
			}
			CampaignFleetAPI extra = FleetFactoryV3.createFleet(params);
			extra.getFleetData().sort();
			for (FleetMemberAPI member : extra.getFleetData().getMembersListCopy()) {
				extra.getFleetData().removeFleetMember(member);
				member.setCaptain(null);
				fleet.getFleetData().addFleetMember(member);
			}
		}
		List<FleetMemberAPI> ships = fleet.getFleetData().getMembersListCopy();
		boolean first = true;
		for (int i=0; i<def.officers.size(); i++) {
			if (i >= ships.size()) break;
			FleetMemberAPI member = ships.get(i);
			PersonAPI officer = createOfficer(def.officers.get(i), member);
			addOfficer(fleet, member, officer);
			
			if (first) {
				fleet.setCommander(officer);
			}
			first = false;
		}
		
		if (!def.noAutofit) {
			DefaultFleetInflaterParams p = new DefaultFleetInflaterParams();
			p.quality = 1.5f;
			if (def.averageSMods != null) {
				p.averageSMods = def.averageSMods;
			}
			p.persistent = true;
			p.seed = intel.seed;
			p.mode = ShipPickMode.PRIORITY_THEN_ALL;
			p.timestamp = Global.getSector().getClock().getTimestamp();
			p.allWeapons = true;
			p.factionId = def.factionId;
			FleetInflater inflater = Misc.getInflater(fleet, p);
			fleet.setInflater(inflater);
			
			inflateFleet(fleet);
		}
		
		fleet.setFaction(def.factionId, true);
		
		return fleet;
	}
	
	public void inflateFleet(CampaignFleetAPI fleet) {
		fleet.inflateIfNeeded();
	}
	
	public void addOfficer(CampaignFleetAPI fleet, FleetMemberAPI member, PersonAPI officer) 
	{
		fleet.getFleetData().addOfficer(officer);
		member.setCaptain(officer);
	}
	
	public PersonAPI createOfficer(OfficerDef def, FleetMemberAPI member) {
		
		// if persistent person, try to get already existing copy
		if (def.persistentId != null) {
			PersonAPI important = Global.getSector().getImportantPeople().getPerson(def.persistentId);
			if (important != null) return important;
		}
		
		SkillPickPreference pref = SkillPickPreference.GENERIC;
		if (member.isCarrier()) pref = SkillPickPreference.CARRIER;
		else if (member.isPhaseShip()) pref = SkillPickPreference.PHASE;
		PersonAPI person; 
		
		if (def.aiCoreId != null) {
			person = Misc.getAICoreOfficerPlugin(def.aiCoreId).createPerson(
					def.aiCoreId, intel.getDef().factionId, new Random());
		} else {
			person = OfficerManagerEvent.createOfficer(intel.getDef().getFaction(), 
				def.level, pref, false, null, true, true, -1, new Random());
		}		
		
		person.setPostId(Ranks.POST_MERCENARY);
		if (def.firstName != null) person.getName().setFirst(def.firstName);
		if (def.lastName != null) person.getName().setLast(def.lastName);
		if (def.gender != null) person.getName().setGender(def.gender);
		if (def.portrait != null) person.setPortraitSprite(def.portrait);
		if (def.rankId != null) person.setRankId(def.rankId);
		if (def.voice != null) person.setVoice(def.voice);
		if (def.persistentId != null) {
			person.setId(def.persistentId);
			Global.getSector().getImportantPeople().addPerson(person);
		}
		
		OfficerLevelupPlugin plugin = (OfficerLevelupPlugin) Global.getSettings().getPlugin("officerLevelUp");
		person.getStats().setLevel(def.level);
		
		if (def.skills != null) {
			// purge existing skills if needed
			List<SkillLevelAPI> existing = person.getStats().getSkillsCopy();
			for (SkillLevelAPI level : existing) {
				person.getStats().setSkillLevel(level.getSkill().getId(), 0);
			}
			for (String skillId : def.skills.keySet()) {
				int level = def.skills.get(skillId);
				person.getStats().setSkillLevel(skillId, level);
			}
		}
		
		
		Misc.setMercenary(person, true);
		Misc.setUnremovable(person, true);
		
		return person;
	}
	
	public boolean isAvailableAt(MarketAPI market) {
		MercCompanyDef def = intel.getDef();
		
		//log.info("Trying availability for company " + def.id);
		Boolean ignoreMarketRelationship = (Boolean)def.miscData.get("ignoreMarketRelationship");
		if (!Boolean.TRUE.equals(ignoreMarketRelationship) && market.getFaction().isHostileTo(def.factionId)) 
		{
			log.info(def.id + " hostile to market, unable to spawn");
			return false;
		}
		
		if (def.minRep != null) {
			if (!Global.getSector().getPlayerFaction().isAtWorst(def.factionId, def.minRep)) 
			{
				return false;
			}
		}
		if (Global.getSector().getPlayerPerson().getStats().getLevel() < def.minLevel)
			return false;
		
		return true;
	}
	
	public void accept() {
		
	}
	
	public void endEvent() {
		
	}
	
	
	public static MercFleetGenPlugin createPlugin(MercContractIntel intel) {
		String className = intel.getDef().plugin;
		if (className == null) className = "exerelin.campaign.intel.merc.MercFleetGenPlugin";
		MercFleetGenPlugin plugin = null;
		try {
			ClassLoader loader = Global.getSettings().getScriptClassLoader();
			Class<?> clazz = loader.loadClass(className);
			plugin = (MercFleetGenPlugin)clazz.newInstance();
			plugin.intel = intel;
		} catch (ClassNotFoundException | IllegalAccessException | InstantiationException ex) {
			log.error("Failed to load merc fleet generator plugin" + className, ex);
		}
		return plugin;
	}
}
