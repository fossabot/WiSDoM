package edu.brandeis.rlearn;

import static spark.Spark.exception;
import static spark.Spark.get;
import static spark.Spark.init;
import static spark.Spark.post;
import static spark.Spark.staticFiles;
import static spark.Spark.webSocket;

import java.text.DecimalFormat;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import edu.brandeis.wisedb.AdvisorAction;
import edu.brandeis.wisedb.AdvisorActionAssign;
import edu.brandeis.wisedb.AdvisorActionProvision;
import edu.brandeis.wisedb.CostUtils;
import edu.brandeis.wisedb.aws.VMType;
import spark.ModelAndView;
import spark.template.velocity.VelocityTemplateEngine;

public class Server {

	private static HashMap<String, Session> sessionMap = new HashMap<>();
	private static HashMap<Integer, String> templates = new HashMap<>();
	private static Map<Integer, Map<VMType, Integer>> latencies = new HashMap<>();
	private static Map<VMType, Integer> forMachine = new HashMap<>();
	private static Map<Integer, String> templateDesc = new HashMap<>();

	public static void main(String[] args) {
		webSocket("/bandit", BanditWebSocket.class);
		staticFiles.location("/assets");

		defineDefaults();

		get("/", (req, res) -> renderFirstPage(req, null));

		//data input workflow:
		post("/sendInitialDataS", (req, res) -> sendInitialDataS(req, null));
		post("/sendInitialDataR", (req, res) -> sendInitialDataR(req, null));
		post("/sendSLA", (req, res) -> getCostsendSLA(req, null));
		post("/sendNumQueries", (req, res) -> sendNumQueries(req, null)); //for slearn
		post("/sendSLA2", (req, res) -> sendSLA2(req, null));

		exception(Exception.class, (e, req, res) -> {
			e.printStackTrace();
		});

		init();
	}

	/* urls */
	public static String renderFirstPage(spark.Request req, String error) {
		Session session = new Session();
		sessionMap.put(req.session().id(), session);

		HashMap<String, Object> model = new HashMap<>();
		model.put("next-Step", "initialForm.vm");

		model.put("templates", templates);
		model.put("latencies", latencies);
		model.put("desc", templateDesc);

		if (error != null) {
			model.put("error", "error.vm");
			model.put("error-message", error);
		}
		return renderTemplate(model, "index.vm");
	}

	public static String sendInitialDataS(spark.Request req, String error) {
		Session session = getSessionFromMap(req);
		session.setLearnType("S");
		return sendInitialData(req, error, session);
	}

	public static String sendInitialDataR(spark.Request req, String error) {
		Session session = getSessionFromMap(req);
		session.setLearnType("R");
		return sendInitialData(req, error, session);
	}

	public static String sendInitialData(spark.Request req, String error, Session session) {
		HashMap model = new HashMap();
		HashMap<Integer, String> templatesChosen = templatesChosen(req.queryParams());

		model.put("templates", templatesChosen);
		if (templatesChosen.isEmpty()) {
			return renderFirstPage(req, "You must choose at least one template");
		}

		session.setTemplates(templatesChosen);
		model.put("next-Step", "chooseSLA.vm");

		return renderTemplate(model, "chooseSLA.vm");
	}

	public static String getCostsendSLA(spark.Request req, String error) {
		HashMap<String, Object> model = new HashMap<String, Object>();
		Session session = getSessionFromMap(req);

		if (req.queryParams("type").isEmpty() | req.queryParams("value").isEmpty()) {
			model.put("next-Step", "chooseSLA.vm");
			model.put("error", "error.vm");
			model.put("error-message", "You must choose an SLA value");
			return renderTemplate(model, "chooseSLA.vm");
		}

		session.addSLA1(req.queryParams("type"), req.queryParams("value"));
		model.put("SLAvalue", req.queryParams("value"));
		model.put("SLAtype", req.queryParams("type"));

		if (session.isSLEARN()) {
			model.put("next-Step", "chooseNumQueries.vm");
			model.put("templates", session.getTemplates());
			return renderTemplate(model, "chooseNumQueries.vm");
		} else {
			model.put("next-Step", "doRLEARN.vm");
			return renderTemplate(model, "doRLEARN.vm");
		}
	}

	public static String sendNumQueries(spark.Request req, String error) {
		Map<String, Object> model = new HashMap<>();
		Session session = getSessionFromMap(req);
		Map<Integer, Integer> queryFreqs = new HashMap<>();

		int empty = 0;
		for (String s : req.queryParams()) {
			if (s.startsWith("templatecount-")) {
				if (req.queryParams(s).isEmpty()) {
					empty++;
				} else {
					int tID = Integer.valueOf(s.substring("templatecount-".length()));
					int freq = Integer.valueOf(req.queryParams(s));
					queryFreqs.put(tID, freq);
				}
			}
		}

		if (empty > 0) {
			model.put("next-Step", "chooseNumQueries.vm");
			model.put("error", "error.vm");
			model.put("error-message", "You must choose a query amount for every template");
			model.put("templates", session.getTemplates());
			return renderTemplate(model, "chooseNumQueries.vm");
		}

		session.setQueryFreqs(queryFreqs);
		session.recommendSLA();
		if (session.isSLEARN()) {
			model.put("next-Step", "chooseSLA2.vm");
			model.put("SLARecs", session.getRecommendations());
			model.put("originalSLA", session.getOriginalSLA());
		}

		return renderTemplate(model, "chooseSLA2.vm");
	}

	public static String sendSLA2(spark.Request req, String error) {
		Map<String, Object> model = new HashMap<String, Object>();
		Session session = getSessionFromMap(req);

		if (session.isSLEARN()) {
			model.put("next-Step", "doSLEARN.vm");
			if (req.queryParams("slaIdx").equals("original")) {
				session.setSLAIndex(-1);
			} else {
				session.setSLAIndex(Integer.valueOf(req.queryParams("slaIdx")));
			}
		}
		List<AdvisorAction> actions = session.doPlacementWithSelected();
		model.put("actions", session.doPlacementWithSelected()
				.stream()
				.map(a -> a.toString())
				.collect(Collectors.toList()));
		
		long numVMs = actions.stream()
				.filter(aa -> aa instanceof AdvisorActionProvision)
				.count();
		
		long numQueries = actions.stream()
				.filter(aa -> aa instanceof AdvisorActionAssign)
				.count();
		
		DecimalFormat df = new DecimalFormat(".###");
		model.put("numVMs", numVMs);
		model.put("numQueries", numQueries);
		model.put("queryDensity", df.format((double)numQueries / (double)numVMs));
		model.put("vms", getVMsForActions(actions));
		
		model.put("sla", session.getSelectedSLA());
		int ffi = 1;
		int ffd = 2;
		int pack9 = 3;
		int wisedb = 4;
		model.put("cost", df.format((double)CostUtils.getCostForPlan(session.getSelectedSLA().getModel().getWorkloadSpecification(), actions)/10.0));
		model.put("ffi", ffi);
		model.put("ffd", ffd);
		model.put("pack9", pack9);
		model.put("wisedb", wisedb);
//session.generateHeuristicCharts(session.getSelectedSLA())
		return renderTemplate(model, "doSLEARN.vm");
	}

	/* helpers */
	private static Session getSessionFromMap(spark.Request req) {
		return sessionMap.get(req.session().id());
	}

	private static String renderTemplate(Object model, String template) {
		return new VelocityTemplateEngine().render(new ModelAndView(model, template));
	}

	public static HashMap<Integer, String> templatesChosen(Set<String> params) {
		HashMap<Integer, String> templatesChosen = new HashMap<>();
		for (String param : params) {
			if (param.contains("template")) {
				templatesChosen.put(Integer.valueOf(param.substring(8)), templates.get(Integer.valueOf(param.substring(8))));
			}
		}

		return templatesChosen;
	}
	
	private static List<VMModel> getVMsForActions(List<AdvisorAction> actions) {
		LinkedList<VMModel> toR = new LinkedList<>();
		
		for (AdvisorAction a : actions) {
			if (a instanceof AdvisorActionProvision) {
				toR.add(new VMModel());
			}
			
			if (a instanceof AdvisorActionAssign) {
				AdvisorActionAssign assign = (AdvisorActionAssign) a;
				toR.peekLast().addQuery(assign.getQueryTypeToAssign());
			}
		}
		
		return toR;
	}

	/* wise-specific stuff */
	public static void defineDefaults() {
		forMachine.put(VMType.T2_SMALL, 20000);
		latencies.put(1, forMachine);

		forMachine = new HashMap<>();
		forMachine.put(VMType.T2_SMALL, 30000);
		latencies.put(2, forMachine);

		forMachine = new HashMap<>();
		forMachine.put(VMType.T2_SMALL, 40000);
		latencies.put(3, forMachine);

		forMachine = new HashMap<>();
		forMachine.put(VMType.T2_SMALL, 52000);
		latencies.put(4, forMachine);

		forMachine = new HashMap<>();
		forMachine.put(VMType.T2_SMALL, 80000);
		latencies.put(5, forMachine);

		templates.put(1, "SQL QUERY 1");
		templates.put(2, "SQL QUERY 2");
		templates.put(3, "SQL QUERY 3");
		templates.put(4, "SQL QUERY 4");
		templates.put(5, "SQL QUERY 5");

		templateDesc.put(1, "Filter over fact table");
		templateDesc.put(2,  "Join fact table with small table");
		templateDesc.put(3,  "Join fact table with small table + aggregate");
		templateDesc.put(4,  "Join fact table with two small table");
		templateDesc.put(5,  "Join fact table with four small tables + aggregate");

	}

}
