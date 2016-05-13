package edu.illinois.library.cantaloupe.resource.iiif.v2;

import edu.illinois.library.cantaloupe.resource.iiif.Feature;
import edu.illinois.library.cantaloupe.resource.iiif.ProcessorFeature;

/**
 * Encapsulates an IIIF "feature" that is application-dependent.
 *
 * @see ProcessorFeature
 */
enum ServiceFeature implements Feature {

    /**
     * The base URI of the service will redirect to the image information
     * document.
     */
    BASE_URI_REDIRECT("baseUriRedirect"),

    /**
     * The canonical image URI HTTP link header is provided on image responses.
     */
    CANONICAL_LINK_HEADER("canonicalLinkHeader"),

    /**
     * The CORS HTTP header is provided on all responses.
     */
    CORS("cors"),

    /**
     * The JSON-LD media type is provided when JSON-LD is requested.
     */
    JSON_LD_MEDIA_TYPE("jsonldMediaType"),

    /**
     * The profile HTTP link header is provided on image responses.
     */
    PROFILE_LINK_HEADER("profileLinkHeader"),

    /**
     * <p>Size of images may be requested in the form “!w,h”.</p>
     *
     * <p>New in Image API 2.1.</p>
     */
    SIZE_BY_CONFINED_WIDTH_HEIGHT("sizeByConfinedWh"),

    /**
     * Deprecated in Image API 2.1.
     * */
    SIZE_BY_WHITELISTED("sizeByWhListed");

    private String name;

    ServiceFeature(String name) {
        this.name = name;
    }

    public String getName() {
        return this.name;
    }

    /**
     * @return The name.
     */
    public String toString() {
        return this.getName();
    }

}
