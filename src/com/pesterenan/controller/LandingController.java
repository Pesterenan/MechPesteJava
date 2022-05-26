package com.pesterenan.controller;

import static com.pesterenan.utils.Status.STATUS_POUSO_AUTOMATICO;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import com.pesterenan.utils.ControlePID;
import com.pesterenan.utils.Modulos;
import com.pesterenan.utils.Navegacao;
import com.pesterenan.utils.Vetor;
import com.pesterenan.view.MainGui;
import com.pesterenan.view.StatusJPanel;

import krpc.client.RPCException;
import krpc.client.StreamException;

public class LandingController extends FlightController implements Runnable {

	private static final int ALTITUDE_POUSO_AUTOMATICO = 8000;

	private ControlePID altitudeAcelPID = new ControlePID();
	private ControlePID velocidadeAcelPID = new ControlePID();
	private Navegacao navegacao = new Navegacao();

	private static double velP = 0.025, velI = 0.001, velD = 0.01;

	private double altitudeDeSobrevoo = 100;
	private double distanciaDaQueima = 0, velocidadeTotal = 0;
	private Map<String, String> comandos = new HashMap<>();
	private boolean executandoPousoAutomatico = false;
	private boolean executandoSobrevoo = false;
	private static boolean descerDoSobrevoo = false;

	public LandingController(Map<String, String> comandos) {
		super(getConexao());
		this.comandos = comandos;
		this.altitudeAcelPID.limitarSaida(0, 1);
		this.velocidadeAcelPID.limitarSaida(0, 1);
		this.velocidadeAcelPID.setLimitePID(0);
		this.altitudeAcelPID.setLimitePID(0);
		StatusJPanel.setStatus(STATUS_POUSO_AUTOMATICO.get());
	}

	@Override
	public void run() {
		if (comandos.get(Modulos.MODULO.get()).equals(Modulos.MODULO_POUSO_SOBREVOAR.get())) {
			this.altitudeDeSobrevoo = Double.parseDouble(comandos.get(Modulos.ALTITUDE_SOBREVOO.get()));
			executandoSobrevoo = true;
			altitudeAcelPID.limitarSaida(-0.5, 1);
			sobrevoarArea();
		}
		if (comandos.get(Modulos.MODULO.get()).equals(Modulos.MODULO_POUSO.get())) {
			pousarAutomaticamente();
		}
	}

	private void sobrevoarArea() {
		try {
			decolar();
			naveAtual.getAutoPilot().engage();
			while (executandoSobrevoo) {
				try {
					if (velHorizontal.get() > 10) {
						navegacao.mirarRetrogrado();
					} else {
						navegacao.mirarRadialDeFora();
					}
					informarCtrlPIDs(altitudeDeSobrevoo);
					double altPID = altitudeAcelPID.computarPID();
					velocidadeAcelPID.setLimitePID(altPID * acelGravidade);
					double velPID = velocidadeAcelPID.computarPID();
					acelerar((float) (velPID));
					if (descerDoSobrevoo == true) {
						naveAtual.getControl().setGear(true);
						altitudeDeSobrevoo = 0;
						checarPouso();
					}
					Thread.sleep(25);
				} catch (RPCException | StreamException | IOException e) {
					StatusJPanel.setStatus("Função abortada.");
					naveAtual.getAutoPilot().disengage();
					break;
				}
			}
		} catch (InterruptedException | RPCException e) {
			StatusJPanel.setStatus("Decolagem abortada.");
			try {
				naveAtual.getAutoPilot().disengage();
			} catch (RPCException e1) {
			}
			return;
		}
	}

	private void pousarAutomaticamente() {
		try {
			decolar();
			acelerar(0.0f);
			naveAtual.getAutoPilot().engage();
			StatusJPanel.setStatus("Iniciando pouso automático em: " + corpoCeleste);
			checarAltitudeParaPouso();
			comecarPousoAutomatico();
		} catch (RPCException | StreamException | InterruptedException | IOException e) {
			StatusJPanel.setStatus("Não foi possível pousar a nave, operação abortada.");
			try {
				naveAtual.getAutoPilot().disengage();
			} catch (RPCException e1) {
			}
		}
	}

	private void checarAltitudeParaPouso() throws RPCException, StreamException, InterruptedException {
		while (!executandoPousoAutomatico) {
			distanciaDaQueima = calcularDistanciaDaQueima();
			if (altitudeSup.get() < ALTITUDE_POUSO_AUTOMATICO) {
				navegacao.mirarRetrogrado();
				naveAtual.getControl().setBrakes(true);
				if (altitudeSup.get() < distanciaDaQueima && velVertical.get() < -1) {
					executandoPousoAutomatico = true;
				}
			}
			Thread.sleep(50);
		}
	}

	private void comecarPousoAutomatico() throws InterruptedException, RPCException, StreamException, IOException {
		StatusJPanel.setStatus("Iniciando Pouso Automático!");
		while (executandoPousoAutomatico) {
			distanciaDaQueima = calcularDistanciaDaQueima();
			informarCtrlPIDs(distanciaDaQueima);
			checarAltitude();
			checarPouso();
			Thread.sleep(25);
		}
	}

	private void informarCtrlPIDs(double distanciaDaQueima) throws RPCException, StreamException {
		altitudeAcelPID.setEntradaPID(altitudeSup.get());
//		altitudeAcelPID.setEntradaPID(ControlePID.interpolacaoLinear(distanciaDaQueima, altitudeSup.get(), 0.75));
		velocidadeAcelPID.setEntradaPID(velVertical.get());
		double valorTEP = calcularTEP();
		velocidadeAcelPID.ajustarPID(valorTEP * velP, valorTEP * velI, valorTEP * velD);
		altitudeAcelPID.setLimitePID(distanciaDaQueima);
	}

	private void checarAltitude() throws RPCException, StreamException, IOException, InterruptedException {
		if (velHorizontal.get() > 3) {
			navegacao.mirarRetrogrado();
		} else {
			navegacao.mirarRadialDeFora();
		}

		double limiarDoPouso = calcularAcelMaxima() * 3;
		velocidadeAcelPID.setLimitePID(-5);
		if (altitudeSup.get() - limiarDoPouso < limiarDoPouso) {
			naveAtual.getControl().setGear(true);
		}

		double acel = altitudeAcelPID.computarPID();
		double vel = velocidadeAcelPID.computarPID();
		double limite = (altitudeSup.get() - limiarDoPouso) / limiarDoPouso;
		acelerar(ControlePID.interpolacaoLinear(vel, acel, limite));
	}

	private void checarPouso() throws RPCException, IOException, InterruptedException {
		switch (naveAtual.getSituation()) {
		case LANDED:
		case SPLASHED:
			StatusJPanel.setStatus("Pouso Finalizado!");
			executandoPousoAutomatico = false;
			executandoSobrevoo = false;
			descerDoSobrevoo = false;
			acelerar(0.0f);
			naveAtual.getControl().setSAS(true);
			naveAtual.getControl().setRCS(true);
			naveAtual.getControl().setBrakes(false);
			naveAtual.getAutoPilot().disengage();
		default:
			break;
		}
	}

	private double calcularDistanciaDaQueima() throws RPCException, StreamException {
		velocidadeTotal = Math.abs(new Vetor(velHorizontal.get(), velVertical.get(), 0).Magnitude());
		double duracaoDaQueima = velocidadeTotal / calcularAcelMaxima();
		double distanciaDaQueima = (calcularAcelMaxima() * duracaoDaQueima) * duracaoDaQueima;
		MainGui.getParametros().getComponent(0).firePropertyChange("distancia", 0, distanciaDaQueima);
		return distanciaDaQueima;
	}

	public static void descer() {
		descerDoSobrevoo = true;

	}
}
