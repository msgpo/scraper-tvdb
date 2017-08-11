/*
 * Copyright 2012 - 2016 Manuel Laggner
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.tinymediamanager.scraper.thetvdb;

import static org.tinymediamanager.scraper.entities.MediaArtwork.MediaArtworkType.ALL;
import static org.tinymediamanager.scraper.entities.MediaArtwork.MediaArtworkType.BACKGROUND;
import static org.tinymediamanager.scraper.entities.MediaArtwork.MediaArtworkType.BANNER;
import static org.tinymediamanager.scraper.entities.MediaArtwork.MediaArtworkType.POSTER;
import static org.tinymediamanager.scraper.entities.MediaArtwork.MediaArtworkType.SEASON;

import java.text.Format;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tinymediamanager.scraper.MediaMetadata;
import org.tinymediamanager.scraper.MediaProviderInfo;
import org.tinymediamanager.scraper.MediaScrapeOptions;
import org.tinymediamanager.scraper.MediaSearchOptions;
import org.tinymediamanager.scraper.MediaSearchResult;
import org.tinymediamanager.scraper.UnsupportedMediaTypeException;
import org.tinymediamanager.scraper.entities.Certification;
import org.tinymediamanager.scraper.entities.MediaArtwork;
import org.tinymediamanager.scraper.entities.MediaArtwork.MediaArtworkType;
import org.tinymediamanager.scraper.entities.MediaCastMember;
import org.tinymediamanager.scraper.entities.MediaCastMember.CastType;
import org.tinymediamanager.scraper.entities.MediaEpisode;
import org.tinymediamanager.scraper.entities.MediaGenres;
import org.tinymediamanager.scraper.entities.MediaType;
import org.tinymediamanager.scraper.http.TmmHttpClient;
import org.tinymediamanager.scraper.mediaprovider.ITvShowArtworkProvider;
import org.tinymediamanager.scraper.mediaprovider.ITvShowMetadataProvider;
import org.tinymediamanager.scraper.util.ApiKey;
import org.tinymediamanager.scraper.util.MetadataUtil;
import org.tinymediamanager.scraper.util.StrgUtils;
import org.tinymediamanager.scraper.util.TvUtils;

import com.uwetrottmann.thetvdb.TheTvdb;
import com.uwetrottmann.thetvdb.entities.Actor;
import com.uwetrottmann.thetvdb.entities.ActorsResponse;
import com.uwetrottmann.thetvdb.entities.Episode;
import com.uwetrottmann.thetvdb.entities.EpisodeResponse;
import com.uwetrottmann.thetvdb.entities.EpisodesResponse;
import com.uwetrottmann.thetvdb.entities.Language;
import com.uwetrottmann.thetvdb.entities.LanguagesResponse;
import com.uwetrottmann.thetvdb.entities.Series;
import com.uwetrottmann.thetvdb.entities.SeriesImageQueryResult;
import com.uwetrottmann.thetvdb.entities.SeriesImageQueryResultResponse;
import com.uwetrottmann.thetvdb.entities.SeriesImagesQueryParam;
import com.uwetrottmann.thetvdb.entities.SeriesImagesQueryParamResponse;
import com.uwetrottmann.thetvdb.entities.SeriesResponse;
import com.uwetrottmann.thetvdb.entities.SeriesResultsResponse;

import net.xeoh.plugins.base.annotations.PluginImplementation;
import okhttp3.OkHttpClient;

/**
 * The Class TheTvDbMetadataProvider.
 * 
 * @author Manuel Laggner
 */
@PluginImplementation
public class TheTvDbMetadataProvider implements ITvShowMetadataProvider, ITvShowArtworkProvider {
  private static final Logger      LOGGER       = LoggerFactory.getLogger(TheTvDbMetadataProvider.class);
  private static TheTvdb           tvdb;
  private static List<Language>    tvdbLanguages;
  private static MediaProviderInfo providerInfo = createMediaProviderInfo();
  private static String            artworkUrl   = "http://thetvdb.com/banners/";

  public TheTvDbMetadataProvider() throws Exception {
    initAPI();
  }

  private static MediaProviderInfo createMediaProviderInfo() {
    MediaProviderInfo providerInfo = new MediaProviderInfo("tvdb", "thetvdb.com",
        "<html><h3>The TV DB</h3><br />An open database for television fans. This scraper is able to scrape TV series metadata and artwork",
        TheTvDbMetadataProvider.class.getResource("/thetvdb_com.png"));
    providerInfo.setVersion(TheTvDbMetadataProvider.class);
    return providerInfo;
  }

  private static synchronized void initAPI() throws Exception {
    if (tvdb == null) {
      try {
        tvdb = new TheTvdb(ApiKey.decryptApikey("7bHHg4k0XhRERM8xd3l+ElhMUXOA5Ou4vQUEzYLGHt8=")) {
          // tell the tmdb api to use our OkHttp client
          private OkHttpClient okHttpClient;

          @Override
          protected synchronized OkHttpClient okHttpClient() {
            if (this.okHttpClient == null) {
              OkHttpClient.Builder builder = TmmHttpClient.newBuilder(true);
              this.setOkHttpClientDefaults(builder);
              this.okHttpClient = builder.build();
            }

            return this.okHttpClient;
          }
        };
        TheTvDbConnectionCounter.trackConnections();
        LanguagesResponse response = tvdb.languages().allAvailable().execute().body();
        tvdbLanguages = response.data;
      }
      catch (Exception e) {
        LOGGER.error("TheTvDbMetadataProvider", e);
        throw e;
      }
    }
  }

  @Override
  public MediaProviderInfo getProviderInfo() {
    return providerInfo;
  }

  @Override
  public MediaMetadata getMetadata(MediaScrapeOptions mediaScrapeOptions) throws Exception {
    LOGGER.debug("getting metadata: " + mediaScrapeOptions);
    switch (mediaScrapeOptions.getType()) {
      case TV_SHOW:
        return getTvShowMetadata(mediaScrapeOptions);

      case TV_EPISODE:
        return getEpisodeMetadata(mediaScrapeOptions);

      default:
        throw new UnsupportedMediaTypeException(mediaScrapeOptions.getType());
    }
  }

  @Override
  public List<MediaSearchResult> search(MediaSearchOptions options) throws Exception {
    LOGGER.debug("search() " + options.toString());
    List<MediaSearchResult> results = new ArrayList<>();

    if (options.getMediaType() != MediaType.TV_SHOW) {
      throw new UnsupportedMediaTypeException(options.getMediaType());
    }

    // detect the string to search
    String searchString = "";
    if (StringUtils.isNotEmpty(options.getQuery())) {
      searchString = options.getQuery();
    }

    // return an empty search result if no query provided
    if (StringUtils.isEmpty(searchString)) {
      return results;
    }

    String language = options.getLanguage().getLanguage();
    String country = options.getCountry().name(); // for passing the country to the scrape

    // search via the api
    List<Series> series = new ArrayList<>();
    synchronized (tvdb) {
      TheTvDbConnectionCounter.trackConnections();
      try {
        SeriesResultsResponse response = tvdb.search().series(searchString, null, null, language).execute().body();
        series.addAll(response.data);
      }
      catch (Exception e) {
        LOGGER.error("problem getting data vom tvdb: " + e.getMessage());
      }
      LOGGER.debug("found " + series.size() + " results with TMDB id");
    }

    if (series.isEmpty()) {
      return results;
    }

    for (Series show : series) {
      MediaSearchResult result = new MediaSearchResult(providerInfo.getId(), options.getMediaType());
      result.setId(show.id.toString());
      result.setTitle(show.seriesName);
      try {
        result.setYear(Integer.parseInt(show.firstAired.substring(0, 4)));
      }
      catch (Exception ignored) {
      }

      if (StringUtils.isNotBlank(show.banner)) {
        result.setPosterUrl(artworkUrl + show.banner);
      }

      float score = MetadataUtil.calculateScore(searchString, show.seriesName);
      if (yearDiffers(options.getYear(), result.getYear())) {
        float diff = (float) Math.abs(options.getYear() - result.getYear()) / 100;
        LOGGER.debug("parsed year does not match search result year - downgrading score by " + diff);
        score -= diff;
      }
      result.setScore(score);

      results.add(result);
    }

    // sort
    Collections.sort(results);
    Collections.reverse(results);

    return results;
  }

  private MediaMetadata getTvShowMetadata(MediaScrapeOptions options) throws Exception {
    MediaMetadata md = new MediaMetadata(providerInfo.getId());
    Integer id = 0;

    // id from result
    if (options.getResult() != null) {
      try {
        id = Integer.parseInt(options.getResult().getId());
      }
      catch (Exception ignored) {
      }
    }

    // do we have an id from the options?
    if (id == 0) {
      try {
        id = Integer.parseInt(options.getId(providerInfo.getId()));
      }
      catch (Exception ignored) {
      }
    }

    // do we have the id in the alternate form?
    if (id == 0) {
      try {
        id = Integer.parseInt(options.getId("tvdb"));
      }
      catch (Exception ignored) {
      }
    }

    if (id == 0) {
      return md;
    }

    Series show = null;
    synchronized (tvdb) {
      try {
        TheTvDbConnectionCounter.trackConnections();
        SeriesResponse response = tvdb.series().series(id, options.getLanguage().getLanguage()).execute().body();
        show = response.data;
      }
      catch (Exception e) {
        LOGGER.error("failed to get meta data: " + e.getMessage());
      }
    }

    if (show == null) {
      return md;
    }

    // populate metadata
    md.setId(providerInfo.getId(), show.id);
    md.setTitle(show.seriesName);
    if (StringUtils.isNotBlank(show.imdbId)) {
      md.setId(MediaMetadata.IMDB, show.imdbId);
    }
    md.setPlot(show.overview);

    try {
      md.setRuntime(Integer.valueOf(show.runtime));
    }
    catch (NumberFormatException e) {
      md.setRuntime(0);
    }

    md.setRating(show.siteRating);
    md.setVoteCount(TvUtils.parseInt(show.siteRatingCount));

    try {
      md.setReleaseDate(StrgUtils.parseDate(show.firstAired));
    }
    catch (ParseException ignored) {
    }

    try {
      Date date = StrgUtils.parseDate(show.firstAired);
      Calendar calendar = Calendar.getInstance();
      calendar.setTime(date);
      int y = calendar.get(Calendar.YEAR);
      md.setYear(y);
      if (y != 0 && md.getTitle().contains(String.valueOf(y))) {
        LOGGER.debug("Weird TVDB entry - removing date " + y + " from title");
        String t = show.seriesName.replaceAll(String.valueOf(y), "").replaceAll("\\(\\)", "").trim();
        md.setTitle(t);
      }
    }
    catch (Exception ignored) {
    }

    md.setStatus(show.status);
    md.addProductionCompany(show.network);

    List<Actor> actors = new ArrayList<>();
    synchronized (tvdb) {
      try {
        TheTvDbConnectionCounter.trackConnections();
        ActorsResponse response = tvdb.series().actors(id).execute().body();
        actors.addAll(response.data);
      }
      catch (Exception e) {
        LOGGER.error("failed to get actors: " + e.getMessage());
      }
    }

    for (Actor actor : actors) {
      MediaCastMember member = new MediaCastMember(CastType.ACTOR);
      member.setName(actor.name);
      member.setCharacter(actor.role);
      if (StringUtils.isNotBlank(actor.image)) {
        member.setImageUrl(actor.image);
      }

      md.addCastMember(member);
    }

    md.addCertification(Certification.findCertification(show.rating));

    // genres
    for (String genreAsString : show.genre) {
      md.addGenre(getTmmGenre(genreAsString));
    }

    return md;
  }

  private MediaMetadata getEpisodeMetadata(MediaScrapeOptions options) throws Exception {
    MediaMetadata md = new MediaMetadata(providerInfo.getId());

    boolean useDvdOrder = false;
    Integer id = 0;

    // id from result
    if (options.getResult() != null) {
      try {
        id = Integer.parseInt(options.getResult().getId());
      }
      catch (Exception ignored) {
      }
    }

    // do we have an id from the options?
    if (id == 0) {
      try {
        id = Integer.parseInt(options.getId(providerInfo.getId()));
      }
      catch (Exception ignored) {
      }
    }

    // do we have the id in the alternate form?
    if (id == 0) {
      try {
        id = Integer.parseInt(options.getId("tvdb"));
      }
      catch (Exception ignored) {
      }
    }

    if (id == 0) {
      return md;
    }

    // get episode number and season number
    int seasonNr = -1;
    int episodeNr = -1;

    try {
      String option = options.getId(MediaMetadata.SEASON_NR);
      if (option != null) {
        seasonNr = Integer.parseInt(options.getId(MediaMetadata.SEASON_NR));
        episodeNr = Integer.parseInt(options.getId(MediaMetadata.EPISODE_NR));
      }
      else {
        seasonNr = Integer.parseInt(options.getId(MediaMetadata.SEASON_NR_DVD));
        episodeNr = Integer.parseInt(options.getId(MediaMetadata.EPISODE_NR_DVD));
        useDvdOrder = true;
      }
    }
    catch (Exception e) {
      LOGGER.warn("error parsing season/episode number");
    }

    String aired = "";
    if (options.getMetadata() != null && options.getMetadata().getReleaseDate() != null) {
      Format formatter = new SimpleDateFormat("yyyy-MM-dd");
      aired = formatter.format(options.getMetadata().getReleaseDate());
    }
    if (aired.isEmpty() && (seasonNr == -1 || episodeNr == -1)) {
      return md; // not even date set? return
    }

    Episode.FullEpisode episode = null;
    synchronized (tvdb) {
      try {
        // TheTvDbConnectionCounter.trackConnections();
        EpisodesResponse response = null;

        // get by season/ep number
        if (useDvdOrder) {
          TheTvDbConnectionCounter.trackConnections();
          response = tvdb.series()
              .episodesQuery(id, null, null, null, seasonNr, (double) episodeNr, null, null, 1, options.getLanguage().getLanguage()).execute().body();
        }
        else {
          TheTvDbConnectionCounter.trackConnections();
          response = tvdb.series().episodesQuery(id, null, seasonNr, episodeNr, null, null, null, null, 1, options.getLanguage().getLanguage())
              .execute().body();
        }

        // not found? try to match by date
        if (response == null && !aired.isEmpty()) {
          TheTvDbConnectionCounter.trackConnections();
          response = tvdb.series().episodesQuery(id, null, null, null, null, null, null, aired, 1, options.getLanguage().getLanguage()).execute()
              .body();
        }

        if (response != null && !response.data.isEmpty()) {
          TheTvDbConnectionCounter.trackConnections();
          EpisodeResponse response1 = tvdb.episodes().get(response.data.get(0).id, options.getLanguage().getLanguage()).execute().body();
          episode = response1.data;
        }

      }
      catch (Exception e) {
        LOGGER.error("failed to get meta data: " + e.getMessage());
      }
    }

    if (episode == null) {
      return md;
    }

    md.setEpisodeNumber(TvUtils.getEpisodeNumber(episode.airedEpisodeNumber));
    md.setSeasonNumber(TvUtils.getSeasonNumber(episode.airedSeason));
    md.setDvdEpisodeNumber(TvUtils.getEpisodeNumber(episode.dvdEpisodeNumber));
    md.setDvdSeasonNumber(TvUtils.getSeasonNumber(episode.dvdSeason));
    md.setAbsoluteNumber(TvUtils.getEpisodeNumber(episode.absoluteNumber));

    md.setTitle(episode.episodeName);
    md.setPlot(episode.overview);
    md.setRating(episode.siteRating);
    md.setVoteCount(TvUtils.parseInt(episode.siteRatingCount));

    try {
      md.setReleaseDate(StrgUtils.parseDate(episode.firstAired));
    }
    catch (ParseException ignored) {
    }
    md.setId(providerInfo.getId(), episode.id);
    if (StringUtils.isNotBlank(episode.imdbId)) {
      md.setId(MediaMetadata.IMDB, episode.imdbId);
    }

    // directors
    for (String director : episode.directors) {
      MediaCastMember cm = new MediaCastMember(CastType.DIRECTOR);
      cm.setName(director);
      md.addCastMember(cm);
    }

    // writers
    for (String writer : episode.writers) {
      MediaCastMember cm = new MediaCastMember(CastType.WRITER);
      cm.setName(writer);
      md.addCastMember(cm);
    }

    // actors (guests?)
    for (String guest : episode.guestStars) {
      MediaCastMember cm = new MediaCastMember(CastType.ACTOR);
      cm.setName(guest);
      md.addCastMember(cm);
    }

    // Thumb
    if (StringUtils.isNotBlank(episode.filename) && options.getArtworkType() == ALL || options.getArtworkType() == MediaArtworkType.THUMB) {
      MediaArtwork ma = new MediaArtwork(providerInfo.getId(), MediaArtworkType.THUMB);
      ma.setPreviewUrl(artworkUrl + episode.filename);
      ma.setDefaultUrl(artworkUrl + episode.filename);
      md.addMediaArt(ma);
    }

    return md;
  }

  @Override
  public List<MediaArtwork> getArtwork(MediaScrapeOptions options) throws Exception {
    LOGGER.debug("getting artwork: " + options);
    List<MediaArtwork> artwork = new ArrayList<>();
    Integer id = 0;

    // id from result
    if (options.getResult() != null) {
      try {
        id = Integer.parseInt(options.getResult().getId());
      }
      catch (Exception ignored) {
      }
    }

    // do we have an id from the options?
    if (id == 0) {
      try {
        id = Integer.parseInt(options.getId(providerInfo.getId()));
      }
      catch (Exception ignored) {
      }
    }

    // do we have the id in the alternate form?
    if (id == 0) {
      try {
        id = Integer.parseInt(options.getId("tvdb"));
      }
      catch (Exception ignored) {
      }
    }

    if (id == 0) {
      return artwork;
    }

    // get artwork from thetvdb
    List<SeriesImageQueryResult> images = new ArrayList<>();
    synchronized (tvdb) {
      try {
        TheTvDbConnectionCounter.trackConnections();

        // get all types of artwork we can get
        SeriesImagesQueryParamResponse response = tvdb.series().imagesQueryParams(id).execute().body();
        for (SeriesImagesQueryParam param : response.data) {
          // season wide not used atm
          if ("seasonwide".equals(param.keyType)) {
            continue;
          }
          if (options.getArtworkType() == ALL || ("fanart".equals(param.keyType) && options.getArtworkType() == BACKGROUND)
              || ("poster".equals(param.keyType) && options.getArtworkType() == POSTER)
              || ("season".equals(param.keyType) && options.getArtworkType() == SEASON)
              // || ("seasonwide".equals(param.keyType) && options.getArtworkType() == SEASON) // not used atm
              || ("series".equals(param.keyType) && options.getArtworkType() == BANNER)) {
            TheTvDbConnectionCounter.trackConnections();
            SeriesImageQueryResultResponse response1 = tvdb.series().imagesQuery(id, param.keyType, null, null, null).execute().body();
            images.addAll(response1.data);
          }
        }
      }
      catch (Exception e) {
        LOGGER.error("failed to get artwork: " + e.getMessage());
      }
    }

    if (images.isEmpty()) {
      return artwork;
    }

    // sort it
    // Collections.sort(images, new ImageComparator(options.getLanguage().getLanguage()));

    // build output
    for (SeriesImageQueryResult image : images) {
      MediaArtwork ma = null;

      // set artwork type
      switch (image.keyType) {
        case "fanart":
          ma = new MediaArtwork(providerInfo.getId(), BACKGROUND);
          break;

        case "poster":
          ma = new MediaArtwork(providerInfo.getId(), POSTER);
          break;

        case "season":
          ma = new MediaArtwork(providerInfo.getId(), SEASON);
          try {
            ma.setSeason(Integer.parseInt(image.subKey));
          }
          catch (Exception e) {
            LOGGER.warn("could not parse season: " + image.subKey);
          }
          break;

        // not used atm
        // case "seasonwide":
        // ma = new MediaArtwork(providerInfo.getId(), SEASON);
        // break;

        case "series":
          ma = new MediaArtwork(providerInfo.getId(), BANNER);
          break;

        default:
          continue;
      }

      // extract image sizes
      if (StringUtils.isNotBlank(image.resolution)) {
        try {
          Pattern pattern = Pattern.compile("([0-9]{3,4})x([0-9]{3,4})");
          Matcher matcher = pattern.matcher(image.resolution);
          if (matcher.matches() && matcher.groupCount() > 1) {
            int width = Integer.parseInt(matcher.group(1));
            int height = Integer.parseInt(matcher.group(2));
            ma.addImageSize(width, height, artworkUrl + image.fileName);

            // set image size
            switch (ma.getType()) {
              case POSTER:
                if (width >= 1000) {
                  ma.setSizeOrder(MediaArtwork.PosterSizes.LARGE.getOrder());
                }
                else if (width >= 500) {
                  ma.setSizeOrder(MediaArtwork.PosterSizes.BIG.getOrder());
                }
                else if (width >= 342) {
                  ma.setSizeOrder(MediaArtwork.PosterSizes.MEDIUM.getOrder());
                }
                else {
                  ma.setSizeOrder(MediaArtwork.PosterSizes.SMALL.getOrder());
                }
                break;

              case BACKGROUND:
                if (width >= 1920) {
                  ma.setSizeOrder(MediaArtwork.FanartSizes.LARGE.getOrder());
                }
                else if (width >= 1280) {
                  ma.setSizeOrder(MediaArtwork.FanartSizes.MEDIUM.getOrder());
                }
                else {
                  ma.setSizeOrder(MediaArtwork.FanartSizes.SMALL.getOrder());
                }
                break;

              default:
                break;
            }
          }
        }
        catch (Exception e) {
          LOGGER.debug("could not extract size from artwork: " + image.resolution);
        }
      }

      // set size for banner & season poster (resolution not in api)
      if (ma.getType() == SEASON) {
        ma.setSizeOrder(MediaArtwork.FanartSizes.LARGE.getOrder());
      }
      else if (ma.getType() == BANNER) {
        ma.setSizeOrder(MediaArtwork.FanartSizes.MEDIUM.getOrder());
      }

      ma.setDefaultUrl(artworkUrl + image.fileName);
      if (StringUtils.isNotBlank(image.thumbnail)) {
        ma.setPreviewUrl(artworkUrl + image.thumbnail);
      }
      else {
        ma.setPreviewUrl(ma.getDefaultUrl());
      }

      // ma.setLanguage(banner.getLanguage());

      artwork.add(ma);
    }

    return artwork;
  }

  @Override
  public List<MediaEpisode> getEpisodeList(MediaScrapeOptions options) throws Exception {
    LOGGER.debug("getting episode list: " + options);
    List<MediaEpisode> episodes = new ArrayList<>();
    Integer id = 0;

    // id from result
    if (options.getResult() != null) {
      try {
        id = Integer.parseInt(options.getResult().getId());
      }
      catch (Exception ignored) {
      }
    }

    // do we have an id from the options?
    if (id == 0) {
      try {
        id = Integer.parseInt(options.getId(providerInfo.getId()));
      }
      catch (Exception ignored) {
      }
    }

    // do we have the id in the alternate form?
    if (id == 0) {
      try {
        id = Integer.parseInt(options.getId("tvdb"));
      }
      catch (Exception ignored) {
      }
    }

    if (id == 0) {
      return episodes;
    }

    List<Episode> eps = new ArrayList<>();
    synchronized (tvdb) {
      try {
        // 100 results per page
        int counter = 1;
        while (true) {
          TheTvDbConnectionCounter.trackConnections();
          EpisodesResponse response = tvdb.series().episodes(id, counter++, options.getLanguage().getLanguage()).execute().body();

          eps.addAll(response.data);
          if (response.data.size() < 100) {
            break;
          }
        }
      }
      catch (Exception e) {
        LOGGER.error("failed to get episode list: " + e.getMessage());
      }
    }

    for (Episode ep : eps) {
      MediaEpisode episode = new MediaEpisode(providerInfo.getId());
      episode.ids.put(providerInfo.getId(), ep.id);
      episode.episode = TvUtils.getEpisodeNumber(ep.airedEpisodeNumber);
      episode.season = TvUtils.getSeasonNumber(ep.airedSeason);
      episode.dvdEpisode = TvUtils.getEpisodeNumber(ep.dvdEpisodeNumber);
      episode.dvdSeason = TvUtils.getSeasonNumber(ep.dvdSeason);
      episode.title = ep.episodeName;
      episode.plot = ep.overview;
      episode.firstAired = ep.firstAired;

      episodes.add(episode);
    }

    return episodes;
  }

  /**
   * Maps scraper Genres to internal TMM genres
   */
  @Deprecated
  private MediaGenres getTmmGenre(String genre) {
    MediaGenres g = null;
    if (genre.isEmpty()) {
      return g;
    }
    // @formatter:off
    else if (genre.equals("Action")) {
      g = MediaGenres.ACTION;
    }
    else if (genre.equals("Adventure")) {
      g = MediaGenres.ADVENTURE;
    }
    else if (genre.equals("Animation")) {
      g = MediaGenres.ANIMATION;
    }
    else if (genre.equals("Children")) {
      g = MediaGenres.FAMILY;
    }
    else if (genre.equals("Comedy")) {
      g = MediaGenres.COMEDY;
    }
    else if (genre.equals("Crime")) {
      g = MediaGenres.CRIME;
    }
    else if (genre.equals("Documentary")) {
      g = MediaGenres.DOCUMENTARY;
    }
    else if (genre.equals("Drama")) {
      g = MediaGenres.DRAMA;
    }
    else if (genre.equals("Family")) {
      g = MediaGenres.FAMILY;
    }
    else if (genre.equals("Fantasy")) {
      g = MediaGenres.FANTASY;
    }
    else if (genre.equals("Food")) {
      g = MediaGenres.DOCUMENTARY;
    }
    else if (genre.equals("Game Show")) {
      g = MediaGenres.GAME_SHOW;
    }
    else if (genre.equals("Home and Garden")) {
      g = MediaGenres.DOCUMENTARY;
    }
    else if (genre.equals("Horror")) {
      g = MediaGenres.HORROR;
    }
    else if (genre.equals("Mini-Series")) {
      g = MediaGenres.SERIES;
    }
    else if (genre.equals("News")) {
      g = MediaGenres.NEWS;
    }
    else if (genre.equals("Reality")) {
      g = MediaGenres.REALITY_TV;
    }
    else if (genre.equals("Science-Fiction")) {
      g = MediaGenres.SCIENCE_FICTION;
    }
    else if (genre.equals("Soap")) {
      g = MediaGenres.SERIES;
    }
    else if (genre.equals("Special Interest")) {
      g = MediaGenres.INDIE;
    }
    else if (genre.equals("Sport")) {
      g = MediaGenres.SPORT;
    }
    else if (genre.equals("Suspense")) {
      g = MediaGenres.SUSPENSE;
    }
    else if (genre.equals("Talk Show")) {
      g = MediaGenres.TALK_SHOW;
    }
    else if (genre.equals("Thriller")) {
      g = MediaGenres.THRILLER;
    }
    else if (genre.equals("Travel")) {
      g = MediaGenres.HOLIDAY;
    }
    else if (genre.equals("Western")) {
      g = MediaGenres.WESTERN;
    }
    // @formatter:on
    if (g == null) {
      g = MediaGenres.getGenre(genre);
    }
    return g;
  }

  /**
   * Is i1 != i2 (when >0)
   */
  private boolean yearDiffers(Integer i1, Integer i2) {
    return i1 != null && i1 != 0 && i2 != null && i2 != 0 && i1 != i2;
  }

  /**********************************************************************
   * local helper classes
   **********************************************************************/
  private static class ImageComparator implements Comparator<SeriesImageQueryResult> {
    private int preferredLangu = 0;
    private int english        = 0;

    private ImageComparator(String language) {
      for (Language lang : tvdbLanguages) {
        if (language.equals(lang.abbreviation)) {
          preferredLangu = lang.id;
        }
        if ("en".equals(lang.abbreviation)) {
          english = lang.id;
        }
      }
    }

    /*
     * sort artwork: primary by language: preferred lang (ie de), en, others; then: score
     */
    @Override
    public int compare(SeriesImageQueryResult arg0, SeriesImageQueryResult arg1) {
      // check if first image is preferred langu

      // FIXME deactivated until tvdb add this in their API responses
      // if (arg0.languageId == preferredLangu && arg1.languageId != preferredLangu) {
      // return -1;
      // }
      //
      // // check if second image is preferred langu
      // if (arg0.languageId != preferredLangu && arg1.languageId == preferredLangu) {
      // return 1;
      // }
      //
      // // check if the first image is en
      // if (arg0.languageId == english && arg1.languageId != english) {
      // return -1;
      // }
      //
      // // check if the second image is en
      // if (arg0.languageId != english && arg1.languageId == english) {
      // return 1;
      // }

      // if rating is the same, return 0
      if (arg0.ratingsInfo.average == arg1.ratingsInfo.average) {
        return 0;
      }

      // we did not sort until here; so lets sort with the rating
      return arg0.ratingsInfo.average > arg1.ratingsInfo.average ? -1 : 1;
    }
  }
}
