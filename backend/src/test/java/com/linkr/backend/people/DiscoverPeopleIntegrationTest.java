package com.linkr.backend.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;
import com.linkr.backend.IntegrationTest;
import com.linkr.backend.linkrequest.LinkRequestService;
import com.linkr.backend.support.TestUsers;
import com.linkr.backend.support.TestUsers.TestUser;

/**
 * Discover People e richieste Link senza evento, con database di test (linkr_test_db).
 * Ogni test e' @Transactional: tutto viene annullato dal rollback.
 * Interessi del catalogo usati: techno, house, uk-garage (musica); la selezione e' sempre scritta dal test.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Transactional
class DiscoverPeopleIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcClient jdbc;

	private TestUsers users;

	private TestUsers users() {
		if (users == null) {
			users = new TestUsers(mockMvc, jdbc, null);
		}
		return users;
	}

	// ======================= opt-in =======================

	@Test
	void discoverableIsFalseByDefaultEverywhere() throws Exception {
		TestUser anna = users().user("dp_default");

		mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, anna.bearer()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.discoverable").value(false));
		assertThat(jdbc.sql("SELECT discoverable FROM app_user WHERE id = :id").param("id", anna.id()).query(Boolean.class).single())
				.isFalse();
		String register = mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"dp_reg\",\"email\":\"dp_reg@example.com\",\"password\":\"password123\",\"displayName\":\"R\"}"))
				.andReturn().getResponse().getContentAsString();
		assertThat((Boolean) JsonPath.read(register, "$.user.discoverable")).isFalse();
	}

	@Test
	void usersCanOptInAndOutAndTheChoiceIsIdempotent() throws Exception {
		TestUser anna = users().user("dp_toggle");

		discovery(anna, "{\"discoverable\":true}").andExpect(status().isOk()).andExpect(jsonPath("$.discoverable").value(true));
		discovery(anna, "{\"discoverable\":true}").andExpect(status().isOk()).andExpect(jsonPath("$.discoverable").value(true));
		mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, anna.bearer()))
				.andExpect(jsonPath("$.discoverable").value(true));
		assertThat(stored(anna)).isTrue();

		discovery(anna, "{\"discoverable\":false}").andExpect(status().isOk()).andExpect(jsonPath("$.discoverable").value(false));
		assertThat(stored(anna)).isFalse();
	}

	@Test
	void discoveryEndpointRequiresAJwtAndAValidBody() throws Exception {
		TestUser anna = users().user("dp_body");

		mockMvc.perform(put("/api/users/me/discovery").contentType(MediaType.APPLICATION_JSON).content("{\"discoverable\":true}"))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		mockMvc.perform(put("/api/users/me/discovery").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt")
				.contentType(MediaType.APPLICATION_JSON).content("{\"discoverable\":true}")).andExpect(status().isUnauthorized());
		for (String body : List.of("{}", "{\"discoverable\":null}", "{\"discoverable\":\"yes\"}", "{\"discoverable\":1}",
				"{not json", "")) {
			int code = discovery(anna, body).andReturn().getResponse().getStatus();
			assertThat(code).as("body: '%s'", body).isEqualTo(400);
		}
		assertThat(stored(anna)).isFalse();      // nessun default implicito: un corpo sbagliato non attiva nulla
	}

	@Test
	void anIdSentInTheBodyCannotChangeSomeoneElse() throws Exception {
		TestUser anna = users().user("dp_self");
		TestUser bea = users().user("dp_other");

		discovery(anna, "{\"discoverable\":true,\"userId\":\"" + bea.id() + "\",\"id\":\"" + bea.id() + "\"}").andExpect(status().isOk());

		assertThat(stored(anna)).isTrue();
		assertThat(stored(bea)).isFalse();
	}

	// ======================= elenco =======================

	@Test
	void listRequiresAJwt() throws Exception {
		mockMvc.perform(get("/api/people")).andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		mockMvc.perform(get("/api/people").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt")).andExpect(status().isUnauthorized());
	}

	@Test
	void reciprocityIsRequiredToSeeOthers() throws Exception {
		TestUser anna = users().user("dp_recip_a");
		TestUser bea = visible("dp_recip_b", "techno");
		interests(anna, "techno");                                  // interessi in comune, ma anna NON e' visibile

		people(anna, "").andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value("Discovery required"));

		discovery(anna, "{\"discoverable\":true}");
		people(anna, "").andExpect(status().isOk()).andExpect(jsonPath("$.items[0].userId").value(bea.id().toString()));
	}

	@Test
	void onlyVisiblePeopleWithASharedInterestAreListedAndNeverMyself() throws Exception {
		TestUser me = visible("dp_me", "techno", "house");
		visible("dp_shared", "house", "uk-garage");
		visible("dp_nothing_shared", "uk-garage");                  // visibile ma senza interessi in comune
		TestUser hidden = users().user("dp_hidden");               // interessi in comune ma NON visibile
		interests(hidden, "techno");
		visible("dp_no_interests");                                 // visibile ma senza interessi

		String body = people(me, "").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

		assertThat((List<String>) JsonPath.read(body, "$.items[*].username")).containsExactly("dp_shared");
		assertThat((Integer) JsonPath.read(body, "$.totalElements")).isEqualTo(1);
		assertThat(body).doesNotContain("dp_me\"").doesNotContain("dp_hidden").doesNotContain("dp_nothing_shared");
	}

	@Test
	void sharedInterestsAreTheRealIntersectionAndSortedByCountThenName() throws Exception {
		TestUser me = visible("dp_sort_me", "techno", "house", "uk-garage");
		visible("dp_sort_zed", "techno", "house", "uk-garage");           // 3 in comune
		visible("dp_sort_bob", "techno", "house");                        // 2, Bob prima di Carl
		visible("dp_sort_carl", "techno", "house");                       // 2
		visible("dp_sort_dan", "uk-garage", "hiking");                    // 1 ("hiking" non e' mio: non deve comparire)

		String body = people(me, "").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

		assertThat((List<String>) JsonPath.read(body, "$.items[*].username"))
				.containsExactly("dp_sort_zed", "dp_sort_bob", "dp_sort_carl", "dp_sort_dan");
		assertThat((List<Integer>) JsonPath.read(body, "$.items[*].sharedInterestsCount")).containsExactly(3, 2, 2, 1);
		assertThat((List<String>) JsonPath.read(body, "$.items[?(@.username=='dp_sort_dan')].sharedInterests[*].slug"))
				.containsExactly("uk-garage");
		assertThat((List<String>) JsonPath.read(body, "$.items[?(@.username=='dp_sort_bob')].sharedInterests[*].slug"))
				.containsExactly("techno", "house");                                 // ordine del catalogo
	}

	@Test
	void responseContainsOnlyPublicFields() throws Exception {
		TestUser me = visible("dp_fields_me", "techno");
		visible("dp_fields_other", "techno");

		String body = people(me, "").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

		Map<String, Object> item = JsonPath.read(body, "$.items[0]");
		assertThat(item.keySet()).containsExactlyInAnyOrder("userId", "username", "displayName", "avatarUrl",
				"sharedInterestsCount", "sharedInterests", "linkStatus", "linkRequestId");
		Map<String, Object> shared = JsonPath.read(body, "$.items[0].sharedInterests[0]");
		assertThat(shared.keySet()).containsExactlyInAnyOrder("id", "slug", "name", "categorySlug");
		assertThat(body).doesNotContain("email").doesNotContain("@example.com").doesNotContain("assword")
				.doesNotContain("role").doesNotContain("latitude").doesNotContain("longitude").doesNotContain("bio");
	}

	@Test
	void emptyWhenIHaveNoInterestsOrNobodyMatches() throws Exception {
		TestUser me = users().user("dp_empty");
		discovery(me, "{\"discoverable\":true}");
		visible("dp_empty_other", "techno");

		people(me, "").andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0))
				.andExpect(jsonPath("$.totalElements").value(0)).andExpect(jsonPath("$.totalPages").value(0));
	}

	@Test
	void personDisappearsAsSoonAsTheyOptOut() throws Exception {
		TestUser me = visible("dp_out_me", "techno");
		TestUser other = visible("dp_out_other", "techno");

		people(me, "").andExpect(jsonPath("$.totalElements").value(1));
		discovery(other, "{\"discoverable\":false}");
		people(me, "").andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void paginationReturnsStableDisjointPagesWithTotals() throws Exception {
		TestUser me = visible("dp_page_me", "techno");
		for (int i = 0; i < 5; i++) {
			visible("dp_page_p" + i, "techno");
		}

		String first = people(me, "page=0&size=2").andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(5))
				.andExpect(jsonPath("$.totalPages").value(3)).andReturn().getResponse().getContentAsString();
		String second = people(me, "page=1&size=2").andReturn().getResponse().getContentAsString();
		String last = people(me, "page=2&size=2").andReturn().getResponse().getContentAsString();
		List<String> all = new java.util.ArrayList<>();
		for (String page : List.of(first, second, last)) {
			all.addAll(JsonPath.read(page, "$.items[*].userId"));
		}
		assertThat(all).hasSize(5).doesNotHaveDuplicates();
		people(me, "page=3&size=2").andExpect(jsonPath("$.items.length()").value(0));
		people(me, "").andExpect(jsonPath("$.size").value(20));
	}

	@Test
	void invalidPagingReturns400() throws Exception {
		TestUser me = visible("dp_bad_page", "techno");
		for (String query : List.of("page=-1", "size=0", "size=51", "page=abc", "size=abc")) {
			people(me, query).andExpect(status().isBadRequest());
		}
	}

	// ======================= Link senza evento =======================

	@Test
	void linkWithoutEventIsCreatedAndAnsweredLikeAnyOther() throws Exception {
		TestUser anna = visible("dp_link_a", "techno");
		TestUser bea = visible("dp_link_b", "techno");

		String sent = sendFromDiscovery(anna, bea.id()).andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.direction").value("OUTGOING"))
				.andExpect(jsonPath("$.eventId").doesNotExist()).andExpect(jsonPath("$.eventTitle").doesNotExist())
				.andExpect(jsonPath("$.otherUser.userId").value(bea.id().toString()))
				.andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(sent, "$.requestId");
		assertThat(sent).doesNotContain("email").doesNotContain("@example.com");

		people(anna, "").andExpect(jsonPath("$.items[0].linkStatus").value("OUTGOING_PENDING"))
				.andExpect(jsonPath("$.items[0].linkRequestId").value(id));
		people(bea, "").andExpect(jsonPath("$.items[0].linkStatus").value("INCOMING_PENDING"));

		mockMvc.perform(post("/api/link-requests/" + id + "/accept").header(HttpHeaders.AUTHORIZATION, bea.bearer()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
		people(anna, "").andExpect(jsonPath("$.items[0].linkStatus").value("ACCEPTED"));
		mockMvc.perform(get("/api/link-requests?direction=all&status=ACCEPTED").header(HttpHeaders.AUTHORIZATION, anna.bearer()))
				.andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.items[0].eventId").doesNotExist());
	}

	@Test
	void theSenderMustBeDiscoverableToo() throws Exception {
		TestUser anna = users().user("dp_nosend_a");
		interests(anna, "techno");
		TestUser bea = visible("dp_nosend_b", "techno");

		sendFromDiscovery(anna, bea.id()).andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value("Discovery required"));
		assertThat(rows()).isZero();
	}

	@Test
	void unavailableRecipientsAllGetTheSameAnswer() throws Exception {
		TestUser anna = visible("dp_na_a", "techno");
		TestUser notVisible = users().user("dp_na_hidden");
		interests(notVisible, "techno");
		TestUser noShared = visible("dp_na_noshared", "uk-garage");
		UUID missing = UUID.randomUUID();

		String reference = null;
		for (UUID recipient : List.of(notVisible.id(), noShared.id(), missing)) {
			String body = sendFromDiscovery(anna, recipient).andExpect(status().isNotFound())
					.andExpect(jsonPath("$.title").value("Person not available")).andReturn().getResponse().getContentAsString();
			if (reference == null) {
				reference = body;
			}
			assertThat(body).isEqualTo(reference);              // nessuna differenza: non si puo' sondare chi esiste o e' nascosto
		}
		assertThat(rows()).isZero();
	}

	@Test
	void selfRequestAndInvalidPayloadsAreRejected() throws Exception {
		TestUser anna = visible("dp_self_a", "techno");

		sendFromDiscovery(anna, anna.id()).andExpect(status().isBadRequest());
		for (String body : List.of("{}", "{\"eventId\":null}", "{\"recipientId\":null}", "{\"recipientId\":\"nope\"}", "{not json", "")) {
			mockMvc.perform(post("/api/link-requests").header(HttpHeaders.AUTHORIZATION, anna.bearer())
					.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
		}
		assertThat(rows()).isZero();
	}

	@Test
	void aSenderIdInTheBodyIsIgnored() throws Exception {
		TestUser anna = visible("dp_spoof_a", "techno");
		TestUser bea = visible("dp_spoof_b", "techno");
		TestUser mallory = visible("dp_spoof_m", "techno");

		mockMvc.perform(post("/api/link-requests").header(HttpHeaders.AUTHORIZATION, mallory.bearer())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"recipientId\":\"" + bea.id() + "\",\"requesterId\":\"" + anna.id() + "\",\"senderId\":\"" + anna.id() + "\"}"))
				.andExpect(status().isCreated());

		assertThat(jdbc.sql("SELECT requester_id FROM link_request").query(UUID.class).single()).isEqualTo(mallory.id());
	}

	@Test
	void thereIsNeverMoreThanOneLinkPerPair() throws Exception {
		TestUser anna = visible("dp_dup_a", "techno");
		TestUser bea = visible("dp_dup_b", "techno");

		sendFromDiscovery(anna, bea.id()).andExpect(status().isCreated());
		sendFromDiscovery(anna, bea.id()).andExpect(status().isConflict());
		sendFromDiscovery(bea, anna.id()).andExpect(status().isConflict());   // in qualunque direzione
		assertThat(rows()).isEqualTo(1);
	}

	@Test
	void aLinkAlreadyCreatedFromAnEventBlocksADiscoverRequestAndViceVersa() throws Exception {
		TestUser anna = visible("dp_mix_a", "techno");
		TestUser bea = visible("dp_mix_b", "techno");
		UUID event = event();
		participate(anna, event);
		participate(bea, event);

		mockMvc.perform(post("/api/link-requests").header(HttpHeaders.AUTHORIZATION, anna.bearer())
				.contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":\"" + event + "\",\"recipientId\":\"" + bea.id() + "\"}"))
				.andExpect(status().isCreated());

		sendFromDiscovery(bea, anna.id()).andExpect(status().isConflict());
		assertThat(rows()).isEqualTo(1);
	}

	@Test
	void declinePrivacyIsPreservedForRequestsFromDiscover() throws Exception {
		TestUser anna = visible("dp_decl_a", "techno");
		TestUser bea = visible("dp_decl_b", "techno");
		String id = JsonPath.read(sendFromDiscovery(anna, bea.id()).andReturn().getResponse().getContentAsString(), "$.requestId");

		mockMvc.perform(post("/api/link-requests/" + id + "/decline").header(HttpHeaders.AUTHORIZATION, bea.bearer()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DECLINED"));

		people(anna, "").andExpect(jsonPath("$.items[0].linkStatus").value("OUTGOING_PENDING"));     // il mittente non scopre il rifiuto
		people(bea, "").andExpect(jsonPath("$.items[0].linkStatus").value("INCOMING_DECLINED"));
		mockMvc.perform(get("/api/link-requests?direction=outgoing").header(HttpHeaders.AUTHORIZATION, anna.bearer()))
				.andExpect(jsonPath("$.items[0].status").value("PENDING")).andExpect(jsonPath("$.items[0].respondedAt").doesNotExist());
		sendFromDiscovery(anna, bea.id()).andExpect(status().isConflict());                           // e non puo' riprovare
	}

	@Test
	void aPendingRequestStaysAnswerableAfterEitherSideHidesAgain() throws Exception {
		TestUser anna = visible("dp_hide_a", "techno");
		TestUser bea = visible("dp_hide_b", "techno");
		String id = JsonPath.read(sendFromDiscovery(anna, bea.id()).andReturn().getResponse().getContentAsString(), "$.requestId");

		discovery(anna, "{\"discoverable\":false}");
		discovery(bea, "{\"discoverable\":false}");

		mockMvc.perform(post("/api/link-requests/" + id + "/accept").header(HttpHeaders.AUTHORIZATION, bea.bearer()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
	}

	@Test
	void antiSpamLimitsApplyToDiscoverRequestsToo() throws Exception {
		TestUser anna = visible("dp_spam_a", "techno");
		for (int i = 0; i < LinkRequestService.MAX_PER_DAY; i++) {
			TestUser target = visible("dp_spam_t" + i, "techno");
			sendFromDiscovery(anna, target.id()).andExpect(status().isCreated());
		}
		TestUser extra = visible("dp_spam_extra", "techno");

		sendFromDiscovery(anna, extra.id()).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.title").value("Too many link requests"));
		assertThat(rows()).isEqualTo(LinkRequestService.MAX_PER_DAY);
	}

	@Test
	void requestsFromEventsStillRequireParticipation() throws Exception {
		TestUser anna = visible("dp_reg_a", "techno");
		TestUser bea = visible("dp_reg_b", "techno");
		UUID event = event();

		// regressione: con un eventId valido valgono ancora le regole dell'evento (anna non partecipa -> 403)
		mockMvc.perform(post("/api/link-requests").header(HttpHeaders.AUTHORIZATION, anna.bearer())
				.contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":\"" + event + "\",\"recipientId\":\"" + bea.id() + "\"}"))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value("Participation required"));
	}

	// ======================= helper =======================

	/** Utente registrato, con gli interessi indicati e visibile in Discover People. */
	private TestUser visible(String username, String... interestSlugs) throws Exception {
		TestUser user = users().user(username);
		if (interestSlugs.length > 0) {
			interests(user, interestSlugs);
		}
		discovery(user, "{\"discoverable\":true}").andExpect(status().isOk());
		return user;
	}

	private void interests(TestUser user, String... slugs) {
		jdbc.sql("INSERT INTO user_interest (user_id, interest_id) SELECT :u, id FROM interest WHERE slug IN (:slugs)")
				.param("u", user.id()).param("slugs", List.of(slugs)).update();
	}

	private ResultActions discovery(TestUser user, String body) throws Exception {
		return mockMvc.perform(put("/api/users/me/discovery").header(HttpHeaders.AUTHORIZATION, user.bearer())
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions people(TestUser user, String query) throws Exception {
		return mockMvc.perform(get("/api/people" + (query.isEmpty() ? "" : "?" + query)).header(HttpHeaders.AUTHORIZATION, user.bearer()));
	}

	private ResultActions sendFromDiscovery(TestUser sender, UUID recipient) throws Exception {
		return mockMvc.perform(post("/api/link-requests").header(HttpHeaders.AUTHORIZATION, sender.bearer())
				.contentType(MediaType.APPLICATION_JSON).content("{\"recipientId\":\"" + recipient + "\"}"));
	}

	private boolean stored(TestUser user) {
		return jdbc.sql("SELECT discoverable FROM app_user WHERE id = :id").param("id", user.id()).query(Boolean.class).single();
	}

	private long rows() {
		return jdbc.sql("SELECT count(*) FROM link_request").query(Long.class).single();
	}

	private UUID event() {
		Instant now = Instant.now();
		return jdbc.sql("""
				INSERT INTO event (title, description, category, status, starts_at, ends_at, venue_name, address,
				                   neighbourhood, latitude, longitude, demo)
				VALUES ('DISC test event', 'Created by a test', 'MUSIC', 'PUBLISHED', :start, :end, 'Test venue',
				        'Test address', 'Test', 45.46, 9.19, true) RETURNING id
				""")
				.param("start", Timestamp.from(now.plus(24, ChronoUnit.HOURS)))
				.param("end", Timestamp.from(now.plus(26, ChronoUnit.HOURS)))
				.query(UUID.class).single();
	}

	private void participate(TestUser user, UUID event) {
		jdbc.sql("INSERT INTO event_participation (user_id, event_id, status, visible_to_others) VALUES (:u, :e, 'GOING', true)")
				.param("u", user.id()).param("e", event).update();
	}

}
