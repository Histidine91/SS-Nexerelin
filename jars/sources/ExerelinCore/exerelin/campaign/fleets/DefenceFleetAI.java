package exerelin.campaign.fleets;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.ai.FleetAssignmentDataAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.util.Misc;
import exerelin.utilities.ExerelinUtilsFleet;
import exerelin.utilities.StringHelper;
import org.apache.log4j.Logger;
import org.lwjgl.util.vector.Vector2f;

public class DefenceFleetAI implements EveryFrameScript
{
    public static Logger log = Global.getLogger(DefenceFleetAI.class);
       
    private final InvasionFleetManager.InvasionFleetData data;
    private float daysTotal = 0.0F;
    private final CampaignFleetAPI fleet;
    private boolean orderedReturn = false;
  
    public DefenceFleetAI(CampaignFleetAPI fleet, InvasionFleetManager.InvasionFleetData data)
    {
        this.fleet = fleet;
        this.data = data;
        giveInitialAssignment();
    }
  
    @Override
    public void advance(float amount)
    {
        float days = Global.getSector().getClock().convertToDays(amount);
        this.daysTotal += days;
        if (this.daysTotal > 150.0F)
        {
            giveStandDownOrders();
            return;
        }
        FleetAssignmentDataAPI assignment = this.fleet.getAI().getCurrentAssignment();
        if (assignment != null)
        {
            float fp = this.fleet.getFleetPoints();
            if (fp < this.data.startingFleetPoints / 2.0F) {
                giveStandDownOrders();
            }
            
            if (orderedReturn)
                return;
        }
        else
        {
            MarketAPI market = data.targetMarket;
            StarSystemAPI system = market.getStarSystem();
            String entityName = data.target.getName();
            
            if (system != null)
            {
                Vector2f dest = Misc.getPointAtRadius(system.getLocation(), 1500.0F);
                LocationAPI loc = Global.getSector().getHyperspace();
                SectorEntityToken token = loc.createToken(dest.x, dest.y);
                String systemBaseName = system.getBaseName();
                
                if (system != this.fleet.getContainingLocation()) {
                  this.fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, token, 1000.0F, StringHelper.getFleetAssignmentString("travellingToStarSystem", systemBaseName));
                }
                if (this.data.noWander) {
                    this.fleet.addAssignment(FleetAssignment.DEFEND_LOCATION, market.getPrimaryEntity(), 40.0F, StringHelper.getFleetAssignmentString("defending", entityName));
                } else if (Math.random() > 0.8D) {
                  this.fleet.addAssignment(FleetAssignment.PATROL_SYSTEM, system.getHyperspaceAnchor(), 40.0F, 
                          StringHelper.getFleetAssignmentString("patrollingAroundStarSystem", systemBaseName));
                } else if (Math.random() > 0.5D) {
                  this.fleet.addAssignment(FleetAssignment.DEFEND_LOCATION, market.getPrimaryEntity(), 40.0F, StringHelper.getFleetAssignmentString("defending", entityName));
                } else {
                  this.fleet.addAssignment(FleetAssignment.PATROL_SYSTEM, system.getStar(), 40.0F, StringHelper.getFleetAssignmentString("attackingStarSystem", systemBaseName));
                }
            }
            else
            {
                this.fleet.addAssignment(FleetAssignment.DEFEND_LOCATION, market.getPrimaryEntity(), 40.0F, StringHelper.getFleetAssignmentString("defending", entityName));
            }

        }
    }
  
    @Override
    public boolean isDone()
    {
        return !this.fleet.isAlive();
    }
  
    @Override
    public boolean runWhilePaused()
    {
        return false;
    }
  
    protected void giveInitialAssignment()
    {
        if (data.noWait) return;
        float daysToOrbit = ExerelinUtilsFleet.getDaysToOrbit(fleet);
        this.fleet.addAssignment(FleetAssignment.ORBIT_PASSIVE, this.data.source, daysToOrbit, StringHelper.getFleetAssignmentString("preparingFor", data.source.getName(), "missionPatrol"));
    }
  
    protected void giveStandDownOrders()
    {
        if (!this.orderedReturn)
        {
            //log.info("Patrol fleet " + this.fleet.getNameWithFaction() + " standing down");
            this.orderedReturn = true;
            this.fleet.clearAssignments();
            
            SectorEntityToken destination = data.source;
            
            this.fleet.addAssignment(FleetAssignment.DELIVER_CREW, destination, 1000.0F, StringHelper.getFleetAssignmentString("returningTo", destination.getName()));
            this.fleet.addAssignment(FleetAssignment.ORBIT_PASSIVE, destination, ExerelinUtilsFleet.getDaysToOrbit(fleet), StringHelper.getFleetAssignmentString("standingDown", null, "missionPatrol"));
            this.fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, destination, 1000.0F);
        }
    }
}
