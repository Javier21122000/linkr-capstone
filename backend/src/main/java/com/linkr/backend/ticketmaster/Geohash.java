package com.linkr.backend.ticketmaster;

/** Codifica geohash (la Discovery API filtra per area con "geoPoint" = geohash, "latlong" e' deprecato). Nessuna dipendenza. */
final class Geohash {

	private static final String BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";

	private Geohash() {
	}

	static String encode(double latitude, double longitude, int precision) {
		double minLat = -90;
		double maxLat = 90;
		double minLon = -180;
		double maxLon = 180;
		StringBuilder hash = new StringBuilder(precision);
		boolean longitudeBit = true;
		int bit = 0;
		int index = 0;
		while (hash.length() < precision) {
			if (longitudeBit) {
				double mid = (minLon + maxLon) / 2;
				if (longitude >= mid) {
					index = (index << 1) | 1;
					minLon = mid;
				}
				else {
					index = index << 1;
					maxLon = mid;
				}
			}
			else {
				double mid = (minLat + maxLat) / 2;
				if (latitude >= mid) {
					index = (index << 1) | 1;
					minLat = mid;
				}
				else {
					index = index << 1;
					maxLat = mid;
				}
			}
			longitudeBit = !longitudeBit;
			if (++bit == 5) {
				hash.append(BASE32.charAt(index));
				bit = 0;
				index = 0;
			}
		}
		return hash.toString();
	}

}
