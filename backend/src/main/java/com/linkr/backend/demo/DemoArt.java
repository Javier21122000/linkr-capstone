package com.linkr.backend.demo;

import java.util.Map;

/**
 * Illustrazioni originali (copertine) e avatar fotografici della demo, serviti dal frontend (frontend/public/demo/; le copertine sono generate da scripts/generate-demo-art.py, gli avatar sono WebP ritagliati dalla griglia fotografica).
 * Sono percorsi relativi al sito: nessun URL esterno, nessuna licenza da attribuire, disponibili anche dopo il riavvio.
 */
public final class DemoArt {

	public static final String CREDIT = "Illustrazione originale LINKR";

	private static final Map<String, String> COVER_ALTS = Map.ofEntries(
			Map.entry("music-dj", "Illustrazione di due piatti da DJ con cuffie"),
			Map.entry("music-jazz", "Illustrazione di una tastiera di pianoforte con note musicali"),
			Map.entry("music-concert", "Illustrazione di un palco con luci e pubblico"),
			Map.entry("food-table", "Illustrazione di una tavola rotonda vista dall'alto con piatti e bicchieri"),
			Map.entry("food-streetfood", "Illustrazione di un chiosco di street food con tendone a strisce"),
			Map.entry("food-brunch", "Illustrazione di un brunch con uovo, croissant e caffè"),
			Map.entry("culture-gallery", "Illustrazione di una galleria d'arte con tre quadri"),
			Map.entry("culture-photo", "Illustrazione di una macchina fotografica con mirino"),
			Map.entry("culture-cinema", "Illustrazione di uno schermo, una pellicola e un ciak"),
			Map.entry("outdoors-park", "Illustrazione di un parco con alberi, panchina e sentiero"),
			Map.entry("outdoors-run", "Illustrazione di una persona che corre al tramonto lungo l'acqua"),
			Map.entry("outdoors-hike", "Illustrazione di montagne con un sentiero e un'escursionista"),
			Map.entry("nightlife-cocktail", "Illustrazione di un cocktail con oliva su sfondo notturno"),
			Map.entry("nightlife-disco", "Illustrazione di una palla da discoteca con raggi di luce e persone che ballano"),
			Map.entry("nightlife-karaoke", "Illustrazione di un microfono con note e testo del karaoke"),
			Map.entry("sport-football", "Illustrazione di un campo da calcio con pallone"),
			Map.entry("sport-basket", "Illustrazione di un canestro con pallone da basket"),
			Map.entry("sport-bike", "Illustrazione di una bicicletta"),
			Map.entry("sport-fitness", "Illustrazione di un manubrio e di un tappetino da allenamento"),
			Map.entry("social-chat", "Illustrazione di fumetti di conversazione sovrapposti"),
			Map.entry("social-games", "Illustrazione di dadi e pedine di un gioco da tavolo"),
			Map.entry("social-languages", "Illustrazione di fumetti con saluti in lingue diverse"));

	private DemoArt() {
	}

	public static String coverUrl(String theme) {
		requireKnown(theme);
		return "/demo/covers/" + theme + ".svg";
	}

	public static String coverAlt(String theme) {
		requireKnown(theme);
		return COVER_ALTS.get(theme);
	}

	public static String avatarUrl(String personaKey) {
		return "/demo/avatars/" + personaKey + ".webp";
	}

	public static Iterable<String> themes() {
		return COVER_ALTS.keySet();
	}

	private static void requireKnown(String theme) {
		if (!COVER_ALTS.containsKey(theme)) {
			throw new IllegalArgumentException("Unknown demo cover theme: " + theme);
		}
	}

}
