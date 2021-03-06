/*
 * All GTAS code is Copyright 2016, The Department of Homeland Security (DHS), U.S. Customs and Border Protection (CBP).
 * 
 * Please see LICENSE.txt for details.
 */
package gov.gtas.services.matcher.quickmatch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.codec.language.DoubleMetaphone;
import org.apache.commons.text.similarity.JaroWinklerDistance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import gov.gtas.services.matcher.quickmatch.QuickMatcherConfig.AccuracyMode;

@Component
public class MatchingContext {

	private final Logger logger = LoggerFactory.getLogger(MatchingContext.class);

	// Set the parts of names that we should combine for full_name and metaphones
	private final String[] NAME_PARTS = { "first_name", "middle_name", "last_name" };
	private final String[] stringAttributes = { "first_name", "middle_name", "last_name", "GNDR_CD", "CTZNSHP_CTRY_CD",
			"DOC_CTRY_CD", "DOC_TYP_NM", "DOC_ID" };

	@Autowired
	private QuickMatcherConfig config;

	public QuickMatcherConfig getConfig() {
		return config;
	}

	/*
	 * The choice of mode determines the subsets of attributes that QuickMatch uses
	 * to match travelers.
	 * 
	 * HighRecall : QuickMatch prioritizes finding as many derogatory matches as
	 * possible, at the cost of more false positives. On average, QuickMatch will
	 * suggest more hits, requiring more time from GTAS users to review cases.
	 * 
	 * HighPrecision : QuickMatch prioritizes suggesting derogatory matches that are
	 * more likely to be correct, at the cost of missing matches for which there is
	 * less evidence. On average, QuickMatch will suggest fewer hits, but GTAS users
	 * will spend less time on incorrect cases.
	 * 
	 * Balanced : QuickMatch suggests a high fraction of the true derogatory matches
	 * while requiring a reasonable amount of GTAS users’ time to review them.
	 * 
	 * BalancedWithTextDistance : In addition to the Balanced algorithm, QuickMatch
	 * combines the text distance of names with matches on other attributes. This
	 * extra algorithm finds more true hits than Balanced mode alone, for some
	 * queries where Balanced mode may struggle. On other queries, though, it simple
	 * returns hits that Balanced mode already found.
	 * 
	 * gtasDefault: The default:
	 * 
	 */
	private String accuracyMode;

	private List<List<String>> matchClauses;

	// Only used for hard-coded clauses in Balanced accuracyMode
	private final JaroWinklerDistance jaroWinklerDistance = new JaroWinklerDistance();
	private double JARO_WINKLER_THRESHOLD = 0.9;

	// Representations of derog list
	private List<HashMap<String, String>> derogList;
	private Map<String, ArrayList<HashMap<String, String>>> derogListByClause;
	private Map<String, Map<String, Set<String>>> clauseValuesToDerogIds;

	public MatchingContext() {
		// this(AccuracyMode.GTAS_DEFAULT.toString(), watchListItems);
	}

	public void setJARO_WINKLER_THRESHOLD(double jARO_WINKLER_THRESHOLD) {
		JARO_WINKLER_THRESHOLD = jARO_WINKLER_THRESHOLD;
	}

	public void initialize(final List<HashMap<String, String>> watchListItems) {

		initializeConfig();

		this.accuracyMode = this.config.getAccuracyMode(); // AccuracyMode.GTAS_DEFAULT.toString();
		this.matchClauses = config.getClausesForAccuracyMode(accuracyMode);

		this.derogList = watchListItems; // derogTravelers.getBatch();
		this.derogList = this.renameAttributes(this.derogList);
		// Split the list into sets where each matchClause applies;
		// Then, for each query, we need only apply a clause to derog records where it
		// is valid.
		this.derogListByClause = splitBatchByClause(this.derogList, this.matchClauses);
		this.clauseValuesToDerogIds = mapClauseValuesToDerogIds(this.derogList, this.matchClauses);

	}

	private void initializeConfig() {

		ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

		try {
			this.config = mapper.readValue(
					Thread.currentThread().getContextClassLoader().getResourceAsStream("QMConfig.yaml"),
					QuickMatcherConfig.class);
		} catch (IOException e) {
			//
			e.printStackTrace();
		}
	}

	private static Map<String, ArrayList<HashMap<String, String>>> splitBatchByClause(
			List<HashMap<String, String>> derogList, List<List<String>> matchClauses) {
		String clausekey;
		Boolean hasClause;
		Map<String, ArrayList<HashMap<String, String>>> batchByClause = new HashMap<>();
		for (List<String> clause : matchClauses) {
			clausekey = clause.toString();
			// Find derog records to which this clause applies
			ArrayList<HashMap<String, String>> subList = new ArrayList<>();
			for (HashMap<String, String> rec : derogList) {
				hasClause = true;
				for (String attribute : clause) {
					if ((!rec.containsKey(attribute)) || rec.get(attribute).equals("")) {
						hasClause = false;
						break;
					}
				}
				if (hasClause) {
					subList.add(rec);
				}
			}
			batchByClause.put(clausekey, subList);
		}
		return batchByClause;
	}

	private static String concatClauseValues(Map<String, String> record, List<String> clause) {
		StringBuilder builder = new StringBuilder(32);
		for (String attribute : clause) {
			if (record.containsKey(attribute)) {
				builder.append(record.get(attribute));
			}
		}
		return builder.toString();
	}

	private static Map<String, Map<String, Set<String>>> mapClauseValuesToDerogIds(
			List<HashMap<String, String>> derogList, List<List<String>> matchClauses) {
		String clauseKey;
		Map<String, Map<String, Set<String>>> clauseValuesToDerogIds = new HashMap<>();
		String concatenated;
		for (List<String> clause : matchClauses) {
			clauseKey = clause.toString();
			// Find derog records to which this clause applies
			HashMap<String, Set<String>> valuesToDerogIds = new HashMap<>();
			for (HashMap<String, String> rec : derogList) {
				concatenated = concatClauseValues(rec, clause);
				if (!concatenated.equals("")) {
					// All clause attributes had values
					if (valuesToDerogIds.containsKey(concatenated)) {
						valuesToDerogIds.get(concatenated).add(rec.get("derogId"));
					} else {
						Set<String> derogIds = new HashSet();
						derogIds.add(rec.get("derogId"));
						valuesToDerogIds.put(concatenated, derogIds);
					}
				}
			}
			clauseValuesToDerogIds.put(clauseKey, valuesToDerogIds);
		}
		return clauseValuesToDerogIds;
	}

	public MatchingResult match(List<HashMap<String, String>> travelers) {

		// Rename attributes
		this.renameAttributes(travelers);

		// Dictionary for match responses, so that each traveler has a single
		// response object that grows as hits are found from different representations
		HashMap<String, DerogResponse> responses = new HashMap<>();

		// Match each queried traveler
		// Travelers will appear once for each document and each citizenship in their
		// query record.
		// If same traveler appears again, find its original response and add
		// the new derog hits
		String gtasId;
		DerogResponse thisResponse;
		for (HashMap<String, String> trav : travelers) {
			thisResponse = matchTraveler(trav);
			gtasId = thisResponse.getGtasId();
			if (responses.containsKey(gtasId)) {
				// Add any new hits to the existing response object
				responses.get(gtasId).addDerogIds(thisResponse.getDerogIds());
			} else {
				// Create a new response object in the dictionary
				responses.put(thisResponse.getGtasId(), thisResponse);
			}
		}

		// Check that each queried traveler has a response
		for (HashMap<String, String> trav : travelers) {
			if (!responses.containsKey(trav.get("gtasId"))) {
				logger.warn("Valid traveler with gtasId {} was dropped from response list.", trav.get("gtasId"));
			}
		}

		// Count hits
		int totalHits = 0;
		for (DerogResponse resp : responses.values()) {
			totalHits += resp.getDerogIds().size();
		}

		return new MatchingResult(totalHits, responses);

	}

	private DerogResponse matchTraveler(HashMap<String, String> traveler) {

		// DerogHits to add to returned DerogResponse
		ArrayList<DerogHit> derogHits = new ArrayList<>();
		// Use set to dedup derogIds
		Set<String> foundDerogIds = new HashSet<>();

		// For each match clause, compare the input traveler to each derog record with
		// that clause
		String clauseAsString = "";
		String concatenated;
		Set<String> clauseHits = new HashSet<>();
		for (List<String> clause : matchClauses) {

			// Find derog hits on this clause
			clauseAsString = clause.toString();
			Map<String, Set<String>> derogForClause = this.clauseValuesToDerogIds.get(clauseAsString);

			concatenated = concatClauseValues(traveler, clause);
			if ((!concatenated.equals("")) && derogForClause.containsKey(concatenated)) {
				// Get derogIds with same concatenated values for the clause
				clauseHits = derogForClause.get(concatenated);
				logger.info("gtasId " + traveler.get("gtasId") + " matches derogIds " + clauseHits.toString()
						+ " on clause " + clause.toString());
				logger.debug("Matched string: {}", concatenated);
			} else {
				clauseHits = new HashSet<>();
			}

			if (this.accuracyMode.equals(AccuracyMode.GTAS_DEFAULT.toString())) {

				for (HashMap<String, String> derogRecord : this.derogList) {
					if (!foundDerogIds.contains(derogRecord.get("derogId"))) {
						if (traveler.get("metaphones").equals(derogRecord.get("metaphones"))
								&& this.goodTextDistance(traveler.get("full_name"), derogRecord.get("full_name"))) {

							logger.info("Text distance hit for traveler={}, derog={}.", traveler.get("full_name"),
									derogRecord.get("full_name"));

							clauseHits.add(derogRecord.get("derogId"));

						}
					}
				}
			}

			// Build DerogHits only for new derogIds for this traveler
			for (String thisDerogId : clauseHits) {
				if (!foundDerogIds.contains(thisDerogId)) {
					if (clause.size() == 1 && clause.get(0).equals("metaphones")) {
						derogHits.add(new DerogHit(thisDerogId, clauseAsString, 0.9f,
								this.derogList.get(0).get(DerogHit.WATCH_LIST_NAME)));
					} else
						derogHits.add(new DerogHit(thisDerogId, clauseAsString, 1f,
								this.derogList.get(0).get(DerogHit.WATCH_LIST_NAME)));
					foundDerogIds.add(thisDerogId);
				}
			}
		}

		if (this.accuracyMode.equals("BalancedWithTextDistance")) {
			for (HashMap<String, String> derogRecord : this.derogList) {
				// Text distance is expensive, so don't do it for derogIds we've already hit
				if (!foundDerogIds.contains(derogRecord.get("derogId"))) {

					// Hard-coding these supporting attributes for now
					if (traveler.get("DOB_Date").equals(derogRecord.get("DOB_Date"))
							&& this.goodTextDistance(traveler.get("full_name"), derogRecord.get("full_name"))) {

						logger.info("Text distance hit for traveler={}, derog={}, and DOB_Date.",
								traveler.get("full_name"), derogRecord.get("full_name"));
						clauseHits.add(derogRecord.get("derogId"));

					} else if (traveler.get("CTZNSHP_CTRY_CD").equals(derogRecord.get("CTZNSHP_CTRY_CD"))
							&& this.goodTextDistance(traveler.get("full_name"), derogRecord.get("full_name"))) {

						logger.info("Text distance hit for traveler={}, derog={}, and CTZNSHP_CTRY_CD.",
								traveler.get("full_name"), derogRecord.get("full_name"));
						clauseHits.add(derogRecord.get("derogId"));
					}
				}
			}

			// Dedup the hits again
			clauseAsString = "[full_name text distance, DOB_Date OR CTZNSHP_CTRY_CD]";
			for (String thisDerogId : clauseHits) {
				if (!foundDerogIds.contains(thisDerogId)) {
					derogHits.add(new DerogHit(thisDerogId, clauseAsString, 1,
							this.derogList.get(0).get(DerogHit.WATCH_LIST_NAME)));
					foundDerogIds.add(thisDerogId);
				}
			}
		}
		if (derogHits.size() == 0) {
			logger.debug("gtasId {} has no matches.", traveler.get("gtasId"));
		}
		return new DerogResponse(traveler.get("gtasId"), derogHits);
	}

	private boolean goodTextDistance(String name1, String name2) {
		return (this.jaroWinklerDistance.apply(name1, name2) > JARO_WINKLER_THRESHOLD);
	}

	// Attribute renames and cleansing
	private List<HashMap<String, String>> renameAttributes(List<HashMap<String, String>> parsedRecords)
			throws IllegalArgumentException {

		DoubleMetaphone dmeta = new DoubleMetaphone();

		// If gtasId and derogId are renamed to same thing, only change the appropriate
		// one.
		// but leave the original renames list untouched.
		HashMap<String, String> batchRenames = new HashMap<>(config.getAttributeRenames());

		// Begin per-record renames and pre-processing
		// Use iterator so we can remove records with critical errors
		Iterator<HashMap<String, String>> recordIterator = parsedRecords.iterator();
		Map<String, String> rec;
		while (recordIterator.hasNext()) {
			rec = recordIterator.next();
			// First rename attributes to align with internal processing
			for (String defaultAttribute : batchRenames.keySet()) {
				String renamed = batchRenames.get(defaultAttribute);
				if (rec.containsKey(renamed)) {
					String value = rec.remove(renamed);
					// Method remove returns previous value
					rec.put(defaultAttribute, value);
				}
			}

			// Standardize case
			for (String attribute : stringAttributes) {
				if (rec.containsKey(attribute)) {
					rec.put(attribute, rec.get(attribute).toUpperCase());
				}
			}

			// If an attribute is missing, set its derived attributes to the empty string
			// Regex cleansing
			for (String attr : rec.keySet()) {
				rec.put(attr, rec.get(attr).replaceAll(config.getDerogFilterOutRegex(), ""));
			}

			// Gender
			if (rec.containsKey("GNDR_CD")) {
				String gender = rec.get("GNDR_CD");
				if (!(gender.equals("M") || gender.equals("F"))) {
					rec.put("GNDR_CD", "");
				}
			} else {
				rec.put("GNDR_CD", "");
			}

			// Build full_name and metaphones from name parts.
			// Method dmeta.doublemetaphone() accepts no spaces,
			// so apply it to name parts individually.
			String full_name = "";
			String metaphones = "";
			for (String namePart : NAME_PARTS) {
				if (rec.containsKey(namePart) && !rec.get(namePart).equals("")) {
					full_name += rec.get(namePart) + " ";
					metaphones += computeMetaphones(dmeta, rec.get(namePart));
				} else {
					rec.put(namePart, "");
				}
			}
			full_name = full_name.trim();
			metaphones = metaphones.trim();
			rec.put("full_name", full_name);
			rec.put("metaphones", metaphones);
		}
		return parsedRecords;
	}

	private static String computeMetaphones(DoubleMetaphone dmeta, String name) {
		StringBuilder builder = new StringBuilder(32);
		for (String part : name.trim().split("\\s+")) {
			builder.append(dmeta.doubleMetaphone(part));
			builder.append(" ");
		}
		return builder.toString().trim();
	}
}
