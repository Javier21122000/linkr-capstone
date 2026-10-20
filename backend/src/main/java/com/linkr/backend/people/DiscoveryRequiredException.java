package com.linkr.backend.people;

/** Reciprocita': per vedere gli altri in Discover People (o scrivere loro) bisogna essere visibili a propria volta. Diventa 403. */
public class DiscoveryRequiredException extends RuntimeException {

	public DiscoveryRequiredException() {
		super("Turn on Discover People in your profile to see other people and be seen by them.");
	}

}
