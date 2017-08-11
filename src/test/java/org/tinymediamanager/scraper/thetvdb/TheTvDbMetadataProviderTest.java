package org.tinymediamanager.scraper.thetvdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.LocaleUtils;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.tinymediamanager.scraper.MediaMetadata;
import org.tinymediamanager.scraper.MediaScrapeOptions;
import org.tinymediamanager.scraper.MediaSearchOptions;
import org.tinymediamanager.scraper.MediaSearchResult;
import org.tinymediamanager.scraper.entities.Certification;
import org.tinymediamanager.scraper.entities.CountryCode;
import org.tinymediamanager.scraper.entities.MediaArtwork;
import org.tinymediamanager.scraper.entities.MediaCastMember;
import org.tinymediamanager.scraper.entities.MediaEpisode;
import org.tinymediamanager.scraper.entities.MediaLanguages;
import org.tinymediamanager.scraper.entities.MediaType;
import org.tinymediamanager.scraper.mediaprovider.ITvShowArtworkProvider;
import org.tinymediamanager.scraper.mediaprovider.ITvShowMetadataProvider;

public class TheTvDbMetadataProviderTest {

  private static ITvShowMetadataProvider metadataProvider;

  @BeforeClass
  public static void setUp() {
    try {
      metadataProvider = new TheTvDbMetadataProvider();
    }
    catch (Exception e) {
      fail(e.getMessage());
    }
  }

  @Test
  public void testSearch() {
    searchShow("Un village français", "fr", "211941", 2009);
    searchShow("Der Mondbár", "de", "81049", 2007);
    searchShow("Psych", "en", "79335", 2006);
    searchShow("You're the Worst", "en", "281776", 2014);
    searchShow("America's Book of Secrets", "en", "256002", 2012);
    searchShow("Rich Man, Poor Man", "en", "77151", 1976);
    searchShow("Drugs, Inc", "en", "174501", 2010);
    searchShow("Yu-Gi-Oh!", "en", "113561", 1998);
    searchShow("What's the Big Idea?", "en", "268282", 2013);
    searchShow("Wallace & Gromit", "en", "78996", 1989);
    searchShow("SOKO Kitzbühel", "de", "101241", 2001);
  }

  private void searchShow(String title, String language, String id, int year) {
    try {
      MediaSearchOptions options = new MediaSearchOptions(MediaType.TV_SHOW, title);
      options.setLanguage(Locale.forLanguageTag(language));

      List<MediaSearchResult> results = metadataProvider.search(options);
      if (results.isEmpty()) {
        Assert.fail("Result empty!");
      }

      MediaSearchResult result = results.get(0);
      assertThat(result.getTitle()).isNotEmpty();
      assertThat(result.getId()).isEqualTo(id);
      assertThat(result.getYear()).isEqualTo(year);
      assertThat(result.getPosterUrl()).isNotEmpty();
    }
    catch (Exception e) {
      e.printStackTrace();
      Assert.fail(e.getMessage());
    }
  }

  @Test
  public void testTvShowScrape() {
    MediaScrapeOptions options = null;
    MediaMetadata md = null;

    /*
     * Psych (79335)
     */
    try {
      options = new MediaScrapeOptions(MediaType.TV_SHOW);
      options.setId(metadataProvider.getProviderInfo().getId(), "79335");
      options.setCountry(CountryCode.US);
      options.setLanguage(LocaleUtils.toLocale(MediaLanguages.en.name()));
      md = metadataProvider.getMetadata(options);

      // did we get metadata?
      assertNotNull("MediaMetadata", md);

      assertEquals("Psych", md.getTitle());
      assertEquals(
          "Thanks to his police officer father's efforts, Shawn Spencer spent his childhood developing a keen eye for detail (and a lasting dislike of his dad).  Years later, Shawn's frequent tips to the police lead to him being falsely accused of a crime he solved.  Now, Shawn has no choice but to use his abilities to perpetuate his cover story: psychic crime-solving powers, all the while dragging his best friend, his dad, and the police along for the ride.",
          md.getPlot());
      assertEquals(2006, md.getYear());
      assertNotEquals(0d, md.getRating());
      assertNotEquals(0, (int) md.getVoteCount());
      assertEquals("Ended", md.getStatus());
      assertThat(md.getProductionCompanies()).isNotEmpty();
      assertEquals(Certification.US_TVPG, md.getCertifications().get(0));
    }
    catch (Exception e) {
      e.printStackTrace();
      fail(e.getMessage());
    }
  }

  @Test
  public void testEpisodeScrape() {
    MediaScrapeOptions options = null;
    MediaMetadata md = null;
    SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy");

    /*
     * Psych (79335)
     */
    try {
      options = new MediaScrapeOptions(MediaType.TV_EPISODE);
      options.setId(metadataProvider.getProviderInfo().getId(), "79335");
      options.setCountry(CountryCode.US);
      options.setLanguage(LocaleUtils.toLocale(MediaLanguages.en.name()));
      options.setId(MediaMetadata.SEASON_NR, "1");
      options.setId(MediaMetadata.EPISODE_NR, "2");
      options.setArtworkType(MediaArtwork.MediaArtworkType.ALL);
      md = metadataProvider.getMetadata(options);

      // did we get metadata?
      assertNotNull("MediaMetadata", md);

      assertThat(md.getEpisodeNumber()).isEqualTo(2);
      assertThat(md.getSeasonNumber()).isEqualTo(1);
      assertThat(md.getDvdEpisodeNumber()).isEqualTo(2);
      assertThat(md.getDvdSeasonNumber()).isEqualTo(1);
      assertThat(md.getTitle()).isEqualTo("The Spelling Bee");
      assertThat(md.getPlot()).startsWith("When what begins as a little competitive sabotage in a regional spelling");
      assertEquals("14-07-2006", sdf.format(md.getReleaseDate()));
      assertEquals(18, md.getCastMembers(MediaCastMember.CastType.ACTOR).size());
      assertThat(md.getCastMembers(MediaCastMember.CastType.DIRECTOR).size()).isGreaterThan(0);
      assertThat(md.getCastMembers(MediaCastMember.CastType.WRITER).size()).isGreaterThan(0);
      assertThat(md.getMediaArt(MediaArtwork.MediaArtworkType.THUMB)).isNotEmpty();
    }
    catch (Exception e) {
      e.printStackTrace();
      fail(e.getMessage());
    }
  }

  @Test
  public void testArtworkScrape() {
    ITvShowArtworkProvider artworkProvider = (ITvShowArtworkProvider) metadataProvider;
    MediaScrapeOptions options = null;

    /*
     * Psych (79335)
     */
    try {
      // all scrape
      options = new MediaScrapeOptions(MediaType.TV_EPISODE);
      options.setId(metadataProvider.getProviderInfo().getId(), "79335");
      options.setArtworkType(MediaArtwork.MediaArtworkType.ALL);

      List<MediaArtwork> artwork = artworkProvider.getArtwork(options);
      assertThat(artwork).isNotEmpty();

      MediaArtwork ma = artwork.get(0);
      assertThat(ma.getDefaultUrl()).isNotEmpty();
      assertThat(ma.getType()).isIn(MediaArtwork.MediaArtworkType.BANNER, MediaArtwork.MediaArtworkType.POSTER,
          MediaArtwork.MediaArtworkType.BACKGROUND, MediaArtwork.MediaArtworkType.SEASON);
      assertThat(ma.getImageSizes()).isNotEmpty();

      // season scrape
      options.setArtworkType(MediaArtwork.MediaArtworkType.SEASON);

      artwork = artworkProvider.getArtwork(options);
      assertThat(artwork).isNotEmpty();

      ma = artwork.get(0);
      assertThat(ma.getDefaultUrl()).isNotEmpty();
      assertThat(ma.getType()).isEqualTo(MediaArtwork.MediaArtworkType.SEASON);
      assertThat(ma.getSeason()).isGreaterThan(-1);
    }
    catch (Exception e) {
      e.printStackTrace();
      fail(e.getMessage());
    }
  }

  @Test
  public void testEpisodeListScrape() {
    MediaScrapeOptions options = null;
    List<MediaEpisode> episodes = null;

    /*
     * Psych (79335)
     */
    try {
      options = new MediaScrapeOptions(MediaType.TV_EPISODE);
      options.setId(metadataProvider.getProviderInfo().getId(), "79335");
      options.setCountry(CountryCode.US);
      options.setLanguage(LocaleUtils.toLocale(MediaLanguages.en.name()));
      options.setArtworkType(MediaArtwork.MediaArtworkType.ALL);
      episodes = metadataProvider.getEpisodeList(options);

      // did we get metadata?
      assertNotNull("episodes", episodes);

      assertThat(episodes.size()).isEqualTo(126);

      MediaEpisode episode = episodes.get(9);
      assertThat(episode.episode).isEqualTo(2);
      assertThat(episode.season).isEqualTo(1);
      assertThat(episode.dvdEpisode).isEqualTo(2);
      assertThat(episode.dvdSeason).isEqualTo(1);
      assertThat(episode.title).isEqualTo("The Spelling Bee");
      assertThat(episode.plot).startsWith("When what begins as a little competitive sabotage in a regional spelling");
      assertThat(episode.firstAired).isEqualTo("2006-07-14");

    }
    catch (Exception e) {
      e.printStackTrace();
      fail(e.getMessage());
    }
  }
}
