package com.pesterenan.controllers;

import static com.pesterenan.MechPeste.getSpaceCenter;

import java.util.Map;

import com.pesterenan.resources.Bundle;
import com.pesterenan.utils.ControlePID;
import com.pesterenan.utils.Module;
import com.pesterenan.utils.Navigation;
import com.pesterenan.utils.Utilities;

import krpc.client.RPCException;
import krpc.client.StreamException;
import krpc.client.services.SpaceCenter.VesselSituation;

public class LandingController extends Controller {

    public static final double MAX_VELOCITY = 5;
    private static final long sleepTime = 50;
    private static final double velP = 0.05;
    private static final double velI = 0.005;
    private static final double velD = 0.001;
    private ControlePID altitudeCtrl;
    private ControlePID velocityCtrl;
    private Navigation navigation;
    private final int HUNDRED_PERCENT = 100;
    private double hoverAltitude;
    private boolean hoveringMode;
    private boolean hoverAfterApproximation;
    private boolean landingMode;
    private MODE currentMode;
    private double altitudeErrorPercentage;
    private float maxTWR;

    public LandingController(Map<String,String> commands) {
        super();
        this.commands = commands;
        this.navigation = new Navigation(getActiveVessel());
        this.initializeParameters();
    }

    private void initializeParameters() {
        altitudeCtrl = new ControlePID(getSpaceCenter(), sleepTime);
        velocityCtrl = new ControlePID(getSpaceCenter(), sleepTime);
        altitudeCtrl.setOutput(0, 1);
        velocityCtrl.setOutput(0, 1);
    }

    @Override
    public void run() {
        if (commands.get(Module.MODULO.get()).equals(Module.HOVERING.get())) {
            hoverArea();
        }
        if (commands.get(Module.MODULO.get()).equals(Module.LANDING.get())) {
            autoLanding();
        }
    }

    private void hoverArea() {
        try {
            hoverAltitude = Double.parseDouble(commands.get(Module.HOVER_ALTITUDE.get()));
            hoveringMode = true;
            ap.engage();
            tuneAutoPilot();
            while (hoveringMode) {
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
                try {
                    altitudeErrorPercentage = surfaceAltitude.get() / hoverAltitude * HUNDRED_PERCENT;
                    // Select which mode depending on altitude error:
                    if (altitudeErrorPercentage > HUNDRED_PERCENT) {
                        currentMode = MODE.GOING_DOWN;
                    } else if (altitudeErrorPercentage < HUNDRED_PERCENT * 0.9) {
                        currentMode = MODE.GOING_UP;
                    } else {
                        currentMode = MODE.HOVERING;
                    }
                    changeControlMode();
                } catch (RPCException | StreamException ignored) {
                }
                Thread.sleep(sleepTime);
            }
        } catch (InterruptedException | RPCException ignored) {
            // disengageAfterException(Bundle.getString("status_liftoff_abort"));
        }
    }

    private void autoLanding() {
        try {
            landingMode = true;
            maxTWR = Float.parseFloat(commands.get(Module.MAX_TWR.get()));
            hoverAfterApproximation = Boolean.parseBoolean(commands.get(Module.HOVER_AFTER_LANDING.get()));
            hoverAltitude = Double.parseDouble(commands.get(Module.HOVER_ALTITUDE.get()));
            if (!hoverAfterApproximation) {
                hoverAltitude = 100;
            }
            setCurrentStatus(Bundle.getString("status_starting_landing_at") + " " + currentBody.getName());
            currentMode = MODE.DEORBITING;
            ap.engage();
            changeControlMode();
            tuneAutoPilot();
            setCurrentStatus(Bundle.getString("status_starting_landing"));
            while (landingMode) {
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
                getActiveVessel().getControl().setBrakes(true);
                changeControlMode();
                Thread.sleep(sleepTime);
            }
        } catch (RPCException | StreamException | InterruptedException e) {
            setCurrentStatus(Bundle.getString("status_ready"));
        }
    }

    private void changeControlMode() throws RPCException, StreamException, InterruptedException {
        adjustPIDbyTWR();
        double velPID, altPID = 0;
        // Change vessel behavior depending on which mode is active
        switch (currentMode) {
            case DEORBITING :
                deOrbitShip();
                currentMode = MODE.WAITING;
                break;
            case WAITING :
                if (verticalVelocity.get() > 0) {
                    setCurrentStatus(Bundle.getString("status_waiting_for_landing"));
                    throttle(0.0f);
                } else {
                    currentMode = MODE.APPROACHING;
                }
                break;
            case APPROACHING :
                altitudeCtrl.reset();
                velocityCtrl.reset();
                altitudeCtrl.setOutput(0, 1);
                velocityCtrl.setOutput(0, 1);
                double currentVelocity = calculateCurrentVelocityMagnitude();
                double zeroVelocity = calculateZeroVelocityMagnitude();
                double landingDistanceThreshold = Math.max(hoverAltitude, getMaxAcel(maxTWR) * 3);
                double threshold = Utilities.clamp(
                        ((currentVelocity + zeroVelocity) - landingDistanceThreshold) / landingDistanceThreshold, 0, 1);
                altPID = altitudeCtrl.calculate(currentVelocity / sleepTime, zeroVelocity / sleepTime);
                velPID = velocityCtrl.calculate(verticalVelocity.get() / sleepTime,
                        (-Utilities.clamp(surfaceAltitude.get() * 0.1, 3, 20) / sleepTime));
                throttle(Utilities.linearInterpolation(velPID, altPID, threshold));
                navigation.aimForLanding();
                if (threshold < 0.15 || surfaceAltitude.get() < landingDistanceThreshold) {
                    hoverAltitude = landingDistanceThreshold;
                    getActiveVessel().getControl().setGear(true);
                    if (hoverAfterApproximation) {
                        landingMode = false;
                        hoverArea();
                        break;
                    }
                    currentMode = MODE.LANDING;
                }
                setCurrentStatus("Se aproximando do momento do pouso...");
                break;
            case GOING_UP :
                altitudeCtrl.reset();
                velocityCtrl.reset();
                altitudeCtrl.setOutput(-0.5, 0.5);
                velocityCtrl.setOutput(-0.5, 0.5);
                altPID = altitudeCtrl.calculate(altitudeErrorPercentage, HUNDRED_PERCENT);
                velPID = velocityCtrl.calculate(verticalVelocity.get(), MAX_VELOCITY);
                throttle(altPID + velPID);
                navigation.aimAtRadialOut();
                setCurrentStatus("Subindo altitude...");
                break;
            case GOING_DOWN :
                altitudeCtrl.reset();
                velocityCtrl.reset();
                controlThrottleByMatchingVerticalVelocity(-MAX_VELOCITY);
                navigation.aimAtRadialOut();
                setCurrentStatus("Baixando altitude...");
                break;
            case LANDING :
                altitudeCtrl.reset();
                velocityCtrl.reset();
                controlThrottleByMatchingVerticalVelocity(
                        horizontalVelocity.get() > 4 ? 0 : -Utilities.clamp(surfaceAltitude.get() * 0.1, 1, 10));
                navigation.aimForLanding();
                setCurrentStatus("Pousando...");
                hasTheVesselLanded();
                break;
            case HOVERING :
                altitudeCtrl.reset();
                velocityCtrl.reset();
                altitudeCtrl.setOutput(-0.5, 0.5);
                velocityCtrl.setOutput(-0.5, 0.5);
                altPID = altitudeCtrl.calculate(altitudeErrorPercentage, HUNDRED_PERCENT);
                velPID = velocityCtrl.calculate(verticalVelocity.get(), 0);
                throttle(altPID + velPID);
                navigation.aimAtRadialOut();
                setCurrentStatus("Sobrevoando area...");
                break;
        }
    }

    private void controlThrottleByMatchingVerticalVelocity(double velocityToMatch)
            throws RPCException, StreamException {
        velocityCtrl.setOutput(0, 1);
        throttle(velocityCtrl.calculate(verticalVelocity.get(), velocityToMatch + horizontalVelocity.get()));
    }

    private void deOrbitShip() throws RPCException, StreamException, InterruptedException {
        throttle(0.0f);
        if (getActiveVessel().getSituation().equals(VesselSituation.ORBITING)
                || getActiveVessel().getSituation().equals(VesselSituation.SUB_ORBITAL)) {
            setCurrentStatus(Bundle.getString("status_going_suborbital"));
            ap.engage();
            getActiveVessel().getControl().setRCS(true);
            while (ap.getError() > 5) {
                navigation.aimForLanding();
                setCurrentStatus(Bundle.getString("status_orienting_ship"));
                ap.wait_();
                Thread.sleep(sleepTime);
            }
            double targetPeriapsis = currentBody.getAtmosphereDepth();
            targetPeriapsis = targetPeriapsis > 0 ? targetPeriapsis / 2 : -currentBody.getEquatorialRadius() / 2;
            while (periapsis.get() > -apoapsis.get()) {
                navigation.aimForLanding();
                throttle(altitudeCtrl.calculate(targetPeriapsis, periapsis.get()));
                setCurrentStatus(Bundle.getString("status_lowering_periapsis"));
                Thread.sleep(sleepTime);
            }
            getActiveVessel().getControl().setRCS(false);
            throttle(0.0f);
        }
    }

    /**
     * Adjust altitude and velocity PID gains according to current ship TWR:
     */
    private void adjustPIDbyTWR() throws RPCException, StreamException {
        double currentTWR = Math.min(getTWR(), maxTWR);
        // double currentTWR = getMaxAcel(maxTWR);
        double pGain = currentTWR / (sleepTime);
        System.out.println(pGain);
        altitudeCtrl.setPIDValues(pGain * 0.1, 0.0002, pGain * 0.1 * 2);
        velocityCtrl.setPIDValues(pGain * 0.1, 0.1, 0.001);
    }

    private boolean hasTheVesselLanded() throws RPCException {
        if (getActiveVessel().getSituation().equals(VesselSituation.LANDED)
                || getActiveVessel().getSituation().equals(VesselSituation.SPLASHED)) {
            setCurrentStatus(Bundle.getString("status_landed"));
            hoveringMode = false;
            landingMode = false;
            throttle(0.0f);
            getActiveVessel().getControl().setSAS(true);
            getActiveVessel().getControl().setRCS(true);
            getActiveVessel().getControl().setBrakes(false);
            ap.disengage();
            return true;
        }
        return false;
    }

    private double calculateCurrentVelocityMagnitude() throws RPCException, StreamException {
        double timeToGround = surfaceAltitude.get() / verticalVelocity.get();
        double horizontalDistance = horizontalVelocity.get() * timeToGround;
        return calculateEllipticTrajectory(horizontalDistance, surfaceAltitude.get());
    }

    private double calculateZeroVelocityMagnitude() throws RPCException, StreamException {
        double zeroVelocityDistance = calculateEllipticTrajectory(horizontalVelocity.get(), verticalVelocity.get());
        double zeroVelocityBurnTime = zeroVelocityDistance / getMaxAcel(maxTWR);
        return zeroVelocityDistance * zeroVelocityBurnTime;
    }

    private double calculateEllipticTrajectory(double a, double b) {
        double semiMajor = Math.max(a * 2, b * 2);
        double semiMinor = Math.min(a * 2, b * 2);
        return Math.PI * Math.sqrt((semiMajor * semiMajor + semiMinor * semiMinor)) / 4;
    }

    private enum MODE {
        DEORBITING, APPROACHING, GOING_UP, HOVERING, GOING_DOWN, LANDING, WAITING
    }
}
