package com.fs.starfarer.api.impl.campaign.rulecmd.newgame;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI.CargoItemType;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.campaign.rules.MemKeys;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.CharacterCreationData;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.rulecmd.AddRemoveCommodity;
import com.fs.starfarer.api.impl.campaign.rulecmd.BaseCommandPlugin;
import com.fs.starfarer.api.util.Misc.Token;
import exerelin.campaign.PlayerFactionStore;
import exerelin.utilities.ExerelinConfig;
import exerelin.utilities.ExerelinFactionConfig;


public class NGCAddStartingShipsByFleetType extends BaseCommandPlugin {
	
	@Override
	public boolean execute(String ruleId, InteractionDialogAPI dialog, List<Token> params, Map<String, MemoryAPI> memoryMap) {
		if (dialog == null) return false;
		
		String fleetTypeStr = params.get(0).getString(memoryMap);
		CharacterCreationData data = (CharacterCreationData) memoryMap.get(MemKeys.LOCAL).get("$characterData");
		ExerelinFactionConfig factionConf = ExerelinConfig.getExerelinFactionConfig(PlayerFactionStore.getPlayerFactionIdNGC());
		List<String> startingVariants = (List<String>)memoryMap.get(MemKeys.LOCAL).get("$startShips_" + fleetTypeStr);
		if (startingVariants == null || startingVariants.isEmpty())
			startingVariants = factionConf.getStartShipsForType(fleetTypeStr, true);
		
		int crew = 0;
		int supplies = 0;
		int machinery = 0;
		int fuel = 0;
		
		for (String variantId : startingVariants)
		{
			try {
				FleetMemberType type = FleetMemberType.SHIP;
				if (variantId.endsWith("_wing")) {
					type = FleetMemberType.FIGHTER_WING; 
				}
				data.addStartingFleetMember(variantId, type);

				FleetMemberAPI temp = Global.getFactory().createFleetMember(type, variantId);
				crew += (int)Math.min(temp.getNeededCrew() * 1.2f, temp.getMaxCrew());
				
				if (fleetTypeStr.equalsIgnoreCase("super"))
				{
					supplies += (int)temp.getCargoCapacity();
				}
				else
				{
					supplies += (int)temp.getCargoCapacity()/2;
					machinery += (int)temp.getCargoCapacity()/8;
				}
				fuel += (int)Math.min(temp.getFuelUse() * 20, temp.getFuelCapacity());

				AddRemoveCommodity.addFleetMemberGainText(temp.getVariant(), dialog.getTextPanel());
			} catch (RuntimeException rex) {	// probably variant not found
				Global.getLogger(this.getClass()).error(rex.getMessage());
				dialog.getTextPanel().addParagraph(rex.getMessage());
			}	
		}
		TextPanelAPI text = dialog.getTextPanel();
		addCargo(data, Commodities.CREW, crew, text);
		addCargo(data, Commodities.SUPPLIES, supplies, text);
		addCargo(data, Commodities.HEAVY_MACHINERY, machinery, text);
		addCargo(data, Commodities.FUEL, fuel, text);
		
		return true;
	}
	
	protected void addCargo(CharacterCreationData data, String commodity, int amount, TextPanelAPI text)
	{
		if (amount <= 0) return;
		data.getStartingCargo().addItems(CargoItemType.RESOURCES, commodity, amount);
		AddRemoveCommodity.addCommodityGainText(commodity, amount, text);
	}
}






