package com.aniliphy.anilist

internal object Queries {
    const val VIEWER = """
query {
  Viewer {
    id
    name
    avatar { large }
    statistics {
      anime { count episodesWatched }
    }
  }
}
"""

    const val OVERVIEW = """
query (${'$'}userId: Int) {
  Viewer {
    id
    name
    avatar { large medium }
    statistics {
      anime {
        count
        episodesWatched
        genres(limit: 10, sort: COUNT_DESC) { genre count }
      }
      manga {
        count
        chaptersRead
        genres(limit: 10, sort: COUNT_DESC) { genre count }
      }
    }
  }
  Page(page: 1, perPage: 15) {
    activities(userId: ${'$'}userId, type: MEDIA_LIST, sort: ID_DESC) {
      ... on ListActivity {
        id
        status
        progress
        createdAt
        media {
          id
          type
          format
          title { romaji english }
          coverImage { extraLarge large }
          episodes
          chapters
        }
      }
    }
  }
}
"""

    private const val CARD = """
      id
      type
      format
      status
      title { romaji english }
      description
      coverImage { extraLarge large }
      averageScore
      episodes
      chapters
      volumes
      genres
      siteUrl
      seasonYear
      startDate { year month day }
      nextAiringEpisode { episode airingAt }
"""

    const val MEDIA_SEARCH = """
query (${'$'}search: String, ${'$'}page: Int, ${'$'}type: MediaType, ${'$'}formats: [MediaFormat]) {
  Page(page: ${'$'}page, perPage: 50) {
    pageInfo { hasNextPage }
    media(search: ${'$'}search, type: ${'$'}type, format_in: ${'$'}formats, sort: SEARCH_MATCH) {$CARD    }
  }
}
"""

    const val SEASON = """
query (${'$'}season: MediaSeason, ${'$'}year: Int, ${'$'}page: Int) {
  Page(page: ${'$'}page, perPage: 25) {
    pageInfo { hasNextPage }
    media(type: ANIME, season: ${'$'}season, seasonYear: ${'$'}year, isAdult: false, sort: POPULARITY_DESC) {$CARD    }
  }
}
"""

    const val SEARCH_EVERYTHING = """
query (${'$'}search: String) {
  anime: Page(perPage: 10) {
    media(search: ${'$'}search, type: ANIME, sort: SEARCH_MATCH) {$CARD    }
  }
  manga: Page(perPage: 10) {
    media(search: ${'$'}search, type: MANGA, format_in: [MANGA, ONE_SHOT], sort: SEARCH_MATCH) {$CARD    }
  }
  novels: Page(perPage: 10) {
    media(search: ${'$'}search, type: MANGA, format: NOVEL, sort: SEARCH_MATCH) {$CARD    }
  }
  characters: Page(perPage: 10) {
    characters(search: ${'$'}search, sort: SEARCH_MATCH) { id name { full native } image { large medium } }
  }
  staff: Page(perPage: 10) {
    staff(search: ${'$'}search, sort: SEARCH_MATCH) { id name { full native } image { large medium } }
  }
  studios: Page(perPage: 10) {
    studios(search: ${'$'}search, sort: SEARCH_MATCH) { id name favourites }
  }
}
"""

    const val CHARACTERS = """
query (${'$'}search: String, ${'$'}page: Int, ${'$'}sort: [CharacterSort]) {
  Page(page: ${'$'}page, perPage: 50) {
    pageInfo { hasNextPage }
    characters(search: ${'$'}search, sort: ${'$'}sort) {
      id
      name { full native }
      image { large medium }
    }
  }
}
"""

    const val STAFF_LIST = """
query (${'$'}search: String, ${'$'}page: Int, ${'$'}sort: [StaffSort]) {
  Page(page: ${'$'}page, perPage: 50) {
    pageInfo { hasNextPage }
    staff(search: ${'$'}search, sort: ${'$'}sort) {
      id
      name { full native }
      image { large medium }
    }
  }
}
"""

    const val STUDIOS = """
query (${'$'}search: String, ${'$'}page: Int, ${'$'}sort: [StudioSort]) {
  Page(page: ${'$'}page, perPage: 50) {
    pageInfo { hasNextPage }
    studios(search: ${'$'}search, sort: ${'$'}sort) {
      id
      name
      favourites
    }
  }
}
"""

    const val DETAILS = """
query (${'$'}id: Int) {
  Media(id: ${'$'}id) {
    id
    type
    format
    isFavourite
    duration
    startDate { year month day }
    endDate { year month day }
    season
    seasonYear
    nextAiringEpisode { episode airingAt }
    source
    title { romaji english native }
    synonyms
    studios { nodes { id name isAnimationStudio } }
    staff(sort: RELEVANCE, perPage: 8) {
      edges { role node { id name { full } image { large medium } } }
    }
    characters(sort: [ROLE, RELEVANCE], perPage: 12) {
      edges {
        role
        node { id name { full } image { large medium } }
        voiceActors(language: JAPANESE) { name { full } }
      }
    }
    relations {
      edges {
        relationType
        node {
          id
          type
          format
          title { romaji english }
          coverImage { extraLarge large }
        }
      }
    }
    recommendations(sort: RATING_DESC, perPage: 15) {
      nodes {
        rating
        mediaRecommendation {
          id
          type
          format
          averageScore
          title { romaji english }
          coverImage { extraLarge large }
        }
      }
    }
  }
}
"""

    const val MEDIA = """
query (${'$'}id: Int) {
  Media(id: ${'$'}id) {
    id
    idMal
    type
    countryOfOrigin
    title { romaji english native }
    description
    coverImage { extraLarge large color }
    bannerImage
    averageScore
    episodes
    chapters
    volumes
    genres
    siteUrl
    format
    seasonYear
    status
    isFavourite
  }
}
"""

    const val CHARACTER = """
query (${'$'}id: Int, ${'$'}page: Int) {
  Character(id: ${'$'}id) {
    id
    isFavourite
    name { full native alternative alternativeSpoiler }
    image { large medium }
    description
    age
    gender
    dateOfBirth { year month day }
    bloodType
    favourites
    media(sort: POPULARITY_DESC, page: ${'$'}page, perPage: 50) {
      pageInfo { hasNextPage }
      edges {
        characterRole
        node {
          id
          type
          format
          title { romaji english }
          coverImage { extraLarge large }
        }
      }
    }
  }
}
"""

    const val STAFF = """
query (${'$'}id: Int, ${'$'}page: Int) {
  Staff(id: ${'$'}id) {
    id
    isFavourite
    name { full native alternative }
    image { large medium }
    description
    age
    gender
    dateOfBirth { year month day }
    bloodType
    favourites
    primaryOccupations
    yearsActive
    homeTown
    staffMedia(sort: POPULARITY_DESC, page: ${'$'}page, perPage: 50) {
      pageInfo { hasNextPage }
      edges {
        staffRole
        node {
          id
          type
          format
          title { romaji english }
          coverImage { extraLarge large }
        }
      }
    }
  }
}
"""

    const val STUDIO = """
query (${'$'}id: Int, ${'$'}page: Int) {
  Studio(id: ${'$'}id) {
    name
    isFavourite
    media(sort: START_DATE_DESC, page: ${'$'}page, perPage: 50) {
      pageInfo { hasNextPage }
      nodes {
        id
        title { romaji english }
        coverImage { extraLarge large }
        format
        episodes
        averageScore
        startDate { year }
      }
    }
  }
}
"""

    const val PROGRESS = """
query (${'$'}id: Int) {
  Media(id: ${'$'}id) {
    episodes
    chapters
    volumes
    mediaListEntry { id progress progressVolumes status score(format: POINT_10_DECIMAL) repeat notes customLists(asArray: true) }
  }
}
"""

    private const val WATCHING_ENTRY = """
      entries {
        status
        updatedAt
        progress
        repeat
        media {
          id
          idMal
          type
          format
          title { romaji english native }
          coverImage { extraLarge large }
          episodes
          nextAiringEpisode { episode airingAt }
        }
      }
"""

    const val WATCHING = """
query (${'$'}userId: Int) {
  MediaListCollection(userId: ${'$'}userId, type: ANIME, status_in: [CURRENT, REPEATING], sort: UPDATED_TIME_DESC) {
    lists {$WATCHING_ENTRY    }
  }
}
"""

    private const val READING_ENTRY = """
      entries {
        status
        updatedAt
        progress
        progressVolumes
        repeat
        media {
          id
          type
          format
          countryOfOrigin
          title { romaji english native }
          coverImage { extraLarge large }
          chapters
          volumes
        }
      }
"""

    const val READING = """
query (${'$'}userId: Int) {
  MediaListCollection(userId: ${'$'}userId, type: MANGA, status_in: [CURRENT, REPEATING], sort: UPDATED_TIME_DESC) {
    lists {$READING_ENTRY    }
  }
}
"""

    const val AIRING_IN_LIST = """
query (${'$'}userId: Int) {
  MediaListCollection(userId: ${'$'}userId, type: ANIME, status_in: [CURRENT, REPEATING, PLANNING, PAUSED]) {
    lists {
      entries {
        status
        progress
        repeat
        media {
          id
          type
          format
          status
          title { romaji english }
          coverImage { extraLarge large }
          episodes
          averageScore
          nextAiringEpisode { episode airingAt }
        }
      }
    }
  }
}
"""

    const val ANIME_DETAILS = """
query (${'$'}userId: Int) {
  MediaListCollection(userId: ${'$'}userId, type: ANIME) {
    lists {
      status
      entries {
        media {
          id
          genres
          startDate { year }
          tags { name rank isMediaSpoiler isGeneralSpoiler }
        }
      }
    }
  }
}
"""

    const val MANGA_DETAILS = """
query (${'$'}userId: Int) {
  MediaListCollection(userId: ${'$'}userId, type: MANGA) {
    lists {
      status
      entries {
        media {
          id
          genres
          startDate { year }
          tags { name rank isMediaSpoiler isGeneralSpoiler }
          staff(perPage: 4, sort: RELEVANCE) { edges { node { id name { full } } } }
        }
      }
    }
  }
}
"""

    const val ANIME_PEOPLE = """
query (${'$'}userId: Int) {
  User(id: ${'$'}userId) {
    statistics {
      anime {
        studios(limit: 30, sort: COUNT_DESC) { count studio { id name isAnimationStudio } }
        staff(limit: 30, sort: COUNT_DESC) { count staff { id name { full } } }
        voiceActors(limit: 30, sort: COUNT_DESC) { count voiceActor { id name { full } } }
      }
    }
  }
}
"""

    const val ALL_ANIME = """
query (${'$'}userId: Int) {
  MediaListCollection(userId: ${'$'}userId, type: ANIME) {
    lists {
      status
      entries {
        id
        updatedAt
        createdAt
        startedAt { year month day }
        completedAt { year month day }
        status
        progress
        score(format: POINT_10_DECIMAL)
        repeat
        notes
        customLists(asArray: true)
        media {
          id
          type
          title { romaji english }
          coverImage { extraLarge large medium }
          episodes
          duration
          format
          averageScore
          genres
          season
          seasonYear
          countryOfOrigin
        }
      }
    }
  }
}
"""

    const val ALL_MANGA = """
query (${'$'}userId: Int) {
  MediaListCollection(userId: ${'$'}userId, type: MANGA) {
    lists {
      status
      entries {
        id
        updatedAt
        createdAt
        startedAt { year month day }
        completedAt { year month day }
        status
        progress
        progressVolumes
        score(format: POINT_10_DECIMAL)
        repeat
        notes
        customLists(asArray: true)
        media {
          id
          type
          title { romaji english }
          coverImage { extraLarge large medium }
          chapters
          volumes
          format
          averageScore
          genres
          season
          seasonYear
          countryOfOrigin
        }
      }
    }
  }
}
"""

    const val FAVOURITES = """
query (${'$'}userId: Int, ${'$'}page: Int) {
  User(id: ${'$'}userId) {
    favourites {
      anime(page: ${'$'}page, perPage: 25) { pageInfo { hasNextPage } nodes { id type title { romaji english } coverImage { extraLarge large } format } }
      manga(page: ${'$'}page, perPage: 25) { pageInfo { hasNextPage } nodes { id type title { romaji english } coverImage { extraLarge large } format } }
      characters(page: ${'$'}page, perPage: 25) { pageInfo { hasNextPage } nodes { id name { full } image { large } } }
      staff(page: ${'$'}page, perPage: 25) { pageInfo { hasNextPage } nodes { id name { full } image { large } } }
      studios(page: ${'$'}page, perPage: 25) { pageInfo { hasNextPage } nodes { id name } }
    }
  }
}
"""

    const val TOGGLE_FAVOURITE = """
mutation (${'$'}animeId: Int, ${'$'}mangaId: Int, ${'$'}characterId: Int, ${'$'}staffId: Int, ${'$'}studioId: Int) {
  ToggleFavourite(
    animeId: ${'$'}animeId
    mangaId: ${'$'}mangaId
    characterId: ${'$'}characterId
    staffId: ${'$'}staffId
    studioId: ${'$'}studioId
  ) {
    anime { nodes { id } }
    manga { nodes { id } }
    characters { nodes { id } }
    staff { nodes { id } }
    studios { nodes { id } }
  }
}
"""

    const val SAVE_PROGRESS = """
mutation (${'$'}mediaId: Int, ${'$'}progress: Int, ${'$'}progressVolumes: Int, ${'$'}status: MediaListStatus, ${'$'}repeat: Int) {
  SaveMediaListEntry(mediaId: ${'$'}mediaId, progress: ${'$'}progress, progressVolumes: ${'$'}progressVolumes, status: ${'$'}status, repeat: ${'$'}repeat) {
    id
    progress
    progressVolumes
    status
    repeat
  }
}
"""

    const val SAVE_ENTRY = """
mutation (${'$'}mediaId: Int, ${'$'}status: MediaListStatus, ${'$'}progress: Int, ${'$'}progressVolumes: Int, ${'$'}scoreRaw: Int, ${'$'}repeat: Int, ${'$'}notes: String, ${'$'}customLists: [String]) {
  SaveMediaListEntry(mediaId: ${'$'}mediaId, status: ${'$'}status, progress: ${'$'}progress, progressVolumes: ${'$'}progressVolumes, scoreRaw: ${'$'}scoreRaw, repeat: ${'$'}repeat, notes: ${'$'}notes, customLists: ${'$'}customLists) {
    id
    status
    progress
    progressVolumes
    repeat
  }
}
"""

    const val DELETE_ENTRY = """
mutation (${'$'}id: Int) {
  DeleteMediaListEntry(id: ${'$'}id) {
    deleted
  }
}
"""

    const val CUSTOM_LISTS = """
query {
  Viewer {
    mediaListOptions {
      animeList { customLists }
      mangaList { customLists }
    }
  }
}
"""

    /** Your banner, bio and the day-by-day activity counts AniList keeps (about six months). */
    const val PROFILE_EXTRAS = """
query (${'$'}userId: Int) {
  User(id: ${'$'}userId) {
    bannerImage
    about(asHtml: false)
    stats { activityHistory { date amount level } }
  }
}
"""

    /** List activity, newest first, optionally only between two times (seconds). */
    const val ACTIVITIES = """
query (${'$'}userId: Int, ${'$'}page: Int, ${'$'}from: Int, ${'$'}to: Int) {
  Page(page: ${'$'}page, perPage: 50) {
    pageInfo { hasNextPage }
    activities(userId: ${'$'}userId, type: MEDIA_LIST, sort: ID_DESC, createdAt_greater: ${'$'}from, createdAt_lesser: ${'$'}to) {
      ... on ListActivity {
        id
        status
        progress
        createdAt
        media { id type format title { romaji english } coverImage { large medium } }
      }
    }
  }
}
"""

    /** How each title on a list relates to others (sequels, prequels, side stories), to group franchises. */
    const val LIST_LINKS = """
query (${'$'}userId: Int, ${'$'}type: MediaType) {
  MediaListCollection(userId: ${'$'}userId, type: ${'$'}type) {
    lists {
      entries {
        media { id relations { edges { relationType node { id type } } } }
      }
    }
  }
}
"""

    /** A batch of titles with their relations, for walking a whole franchise in watch order. */
    const val FRANCHISE_BATCH = """
query (${'$'}ids: [Int]) {
  Page(perPage: 50) {
    media(id_in: ${'$'}ids) {
      id
      type
      format
      status
      title { romaji english }
      coverImage { large medium }
      episodes
      chapters
      volumes
      startDate { year month day }
      relations { edges { relationType node { id type } } }
    }
  }
}
"""

    private const val USER_CARD = "id name avatar { large medium }"

    /** Someone's profile: who they are, whether you follow each other, and their totals. */
    const val USER = """
query (${'$'}id: Int, ${'$'}name: String) {
  User(id: ${'$'}id, name: ${'$'}name) {
    $USER_CARD
    bannerImage
    about(asHtml: false)
    isFollowing
    isFollower
    statistics {
      anime { count episodesWatched meanScore }
      manga { count chaptersRead meanScore }
    }
    stats { activityHistory { date amount level } }
  }
}
"""

    const val USER_SEARCH = """
query (${'$'}search: String, ${'$'}page: Int) {
  Page(page: ${'$'}page, perPage: 30) {
    pageInfo { hasNextPage }
    users(search: ${'$'}search, sort: SEARCH_MATCH) { $USER_CARD }
  }
}
"""

    const val FOLLOWING = """
query (${'$'}userId: Int!, ${'$'}page: Int) {
  Page(page: ${'$'}page, perPage: 50) {
    pageInfo { hasNextPage }
    following(userId: ${'$'}userId, sort: USERNAME) { $USER_CARD }
  }
}
"""

    const val FOLLOWERS = """
query (${'$'}userId: Int!, ${'$'}page: Int) {
  Page(page: ${'$'}page, perPage: 50) {
    pageInfo { hasNextPage }
    followers(userId: ${'$'}userId, sort: USERNAME) { $USER_CARD }
  }
}
"""

    const val TOGGLE_FOLLOW = """
mutation (${'$'}id: Int) {
  ToggleFollow(userId: ${'$'}id) { id isFollowing }
}
"""

    private const val SOCIAL_ACTIVITY = """
      ... on ListActivity {
        id type status progress createdAt likeCount replyCount isLiked
        user { id name avatar { large medium } }
        media { id type format title { romaji english } coverImage { large medium } }
      }
      ... on TextActivity {
        id type text(asHtml: false) createdAt likeCount replyCount isLiked
        user { id name avatar { large medium } }
      }
"""

    /** What the people you follow did, newest first. */
    const val FEED = """
query (${'$'}page: Int) {
  Page(page: ${'$'}page, perPage: 30) {
    pageInfo { hasNextPage }
    activities(isFollowing: true, type_in: [ANIME_LIST, MANGA_LIST, TEXT], sort: ID_DESC) {
$SOCIAL_ACTIVITY
    }
  }
}
"""

    const val TOGGLE_LIKE = """
mutation (${'$'}id: Int) {
  ToggleLikeV2(id: ${'$'}id, type: ACTIVITY) {
    ... on ListActivity { id likeCount isLiked }
    ... on TextActivity { id likeCount isLiked }
  }
}
"""

    const val REPLIES = """
query (${'$'}id: Int, ${'$'}page: Int) {
  Page(page: ${'$'}page, perPage: 50) {
    pageInfo { hasNextPage }
    activityReplies(activityId: ${'$'}id) {
      id text(asHtml: false) createdAt likeCount
      user { id name avatar { large medium } }
    }
  }
}
"""

    const val SAVE_REPLY = """
mutation (${'$'}activityId: Int, ${'$'}text: String) {
  SaveActivityReply(activityId: ${'$'}activityId, text: ${'$'}text) { id }
}
"""

    /** The people you follow who have [mediaId] on their list. */
    const val FRIENDS_ON_MEDIA = """
query (${'$'}mediaId: Int) {
  Page(perPage: 50) {
    mediaList(mediaId: ${'$'}mediaId, isFollowing: true, sort: UPDATED_TIME_DESC) {
      status progress progressVolumes score(format: POINT_10_DECIMAL)
      user { id name avatar { large medium } }
    }
  }
}
"""

    private const val THREAD_FIELDS = """
id title replyCount viewCount likeCount isLiked isSticky isLocked isSubscribed repliedAt createdAt
user { id name avatar { large medium } }
replyUser { id name avatar { large medium } }
categories { id name }
mediaCategories { id type format title { romaji english native userPreferred } coverImage { large medium } }
"""

    /** Forum threads: all of them, a category's, a title's, or found by [search]. */
    const val THREADS = """
query (${'$'}page: Int, ${'$'}categoryId: Int, ${'$'}mediaCategoryId: Int, ${'$'}search: String, ${'$'}subscribed: Boolean, ${'$'}sort: [ThreadSort]) {
  Page(page: ${'$'}page, perPage: 25) {
    pageInfo { hasNextPage }
    threads(categoryId: ${'$'}categoryId, mediaCategoryId: ${'$'}mediaCategoryId, search: ${'$'}search, subscribed: ${'$'}subscribed, sort: ${'$'}sort) { $THREAD_FIELDS }
  }
}
"""

    const val THREAD = """
query (${'$'}id: Int) {
  Thread(id: ${'$'}id) { $THREAD_FIELDS body(asHtml: false) }
}
"""

    /** Top-level comments; their replies come nested in childComments, with the same fields. */
    const val THREAD_COMMENTS = """
query (${'$'}threadId: Int, ${'$'}page: Int) {
  Page(page: ${'$'}page, perPage: 25) {
    pageInfo { hasNextPage }
    threadComments(threadId: ${'$'}threadId, sort: ID) {
      id comment(asHtml: false) likeCount isLiked createdAt
      user { id name avatar { large medium } }
      childComments
    }
  }
}
"""

    const val SAVE_THREAD_COMMENT = """
mutation (${'$'}threadId: Int, ${'$'}parentCommentId: Int, ${'$'}comment: String) {
  SaveThreadComment(threadId: ${'$'}threadId, parentCommentId: ${'$'}parentCommentId, comment: ${'$'}comment) { id }
}
"""

    const val SAVE_THREAD = """
mutation (${'$'}title: String, ${'$'}body: String, ${'$'}categories: [Int], ${'$'}mediaCategories: [Int]) {
  SaveThread(title: ${'$'}title, body: ${'$'}body, categories: ${'$'}categories, mediaCategories: ${'$'}mediaCategories) { id }
}
"""

    const val TOGGLE_THREAD_LIKE = """
mutation (${'$'}id: Int, ${'$'}type: LikeableType) {
  ToggleLikeV2(id: ${'$'}id, type: ${'$'}type) {
    ... on Thread { id likeCount isLiked }
    ... on ThreadComment { id likeCount isLiked }
  }
}
"""

    const val TOGGLE_SUBSCRIPTION = """
mutation (${'$'}threadId: Int, ${'$'}subscribe: Boolean) {
  ToggleThreadSubscription(threadId: ${'$'}threadId, subscribe: ${'$'}subscribe) { id isSubscribed }
}
"""

    /** A private message to someone, used to recommend them a title. */
    const val SEND_MESSAGE = """
mutation (${'$'}recipientId: Int, ${'$'}message: String) {
  SaveMessageActivity(recipientId: ${'$'}recipientId, message: ${'$'}message, private: true) { id }
}
"""

    private const val NOTE_MEDIA = """media { id type format title { romaji english native userPreferred } coverImage { large medium } }"""
    private const val NOTE_USER = """user { id name avatar { large medium } }"""

    /** Your AniList notifications, newest first; [reset] marks them read. */
    const val NOTIFICATIONS = """
query (${'$'}page: Int, ${'$'}reset: Boolean, ${'$'}types: [NotificationType]) {
  Page(page: ${'$'}page, perPage: 30) {
    pageInfo { hasNextPage }
    notifications(resetNotificationCount: ${'$'}reset, type_in: ${'$'}types) {
      ... on AiringNotification { id type createdAt episode contexts $NOTE_MEDIA }
      ... on FollowingNotification { id type createdAt context $NOTE_USER }
      ... on ActivityMessageNotification { id type createdAt context activityId $NOTE_USER }
      ... on ActivityMentionNotification { id type createdAt context activityId $NOTE_USER }
      ... on ActivityReplyNotification { id type createdAt context activityId $NOTE_USER }
      ... on ActivityReplySubscribedNotification { id type createdAt context activityId $NOTE_USER }
      ... on ActivityLikeNotification { id type createdAt context activityId $NOTE_USER }
      ... on ActivityReplyLikeNotification { id type createdAt context activityId $NOTE_USER }
      ... on ThreadCommentMentionNotification { id type createdAt context commentId thread { id title } $NOTE_USER }
      ... on ThreadCommentReplyNotification { id type createdAt context commentId thread { id title } $NOTE_USER }
      ... on ThreadCommentSubscribedNotification { id type createdAt context commentId thread { id title } $NOTE_USER }
      ... on ThreadCommentLikeNotification { id type createdAt context commentId thread { id title } $NOTE_USER }
      ... on ThreadLikeNotification { id type createdAt context threadId thread { id title } $NOTE_USER }
      ... on RelatedMediaAdditionNotification { id type createdAt context $NOTE_MEDIA }
      ... on MediaDataChangeNotification { id type createdAt context reason $NOTE_MEDIA }
      ... on MediaMergeNotification { id type createdAt context reason deletedMediaTitles $NOTE_MEDIA }
      ... on MediaDeletionNotification { id type createdAt context reason deletedMediaTitle }
    }
  }
}
"""

    const val UNREAD = """
query { Viewer { unreadNotificationCount } }
"""

    /** Episodes airing between two times (seconds), for titles in [ids]. */
    const val AIRING_WEEK = """
query (${'$'}ids: [Int], ${'$'}from: Int, ${'$'}to: Int, ${'$'}page: Int) {
  Page(page: ${'$'}page, perPage: 50) {
    pageInfo { hasNextPage }
    airingSchedules(mediaId_in: ${'$'}ids, airingAt_greater: ${'$'}from, airingAt_lesser: ${'$'}to, sort: TIME) {
      id episode airingAt
      media { id type format episodes duration title { romaji english native userPreferred } coverImage { large medium } }
    }
  }
}
"""

    /** A season's anime, most popular first. */
    const val SEASON_EXPLORE = """
query (${'$'}season: MediaSeason, ${'$'}year: Int, ${'$'}page: Int, ${'$'}sort: [MediaSort]) {
  Page(page: ${'$'}page, perPage: 50) {
    pageInfo { hasNextPage }
    media(season: ${'$'}season, seasonYear: ${'$'}year, type: ANIME, isAdult: false, sort: ${'$'}sort) {
      id type format status episodes duration source season seasonYear popularity averageScore genres description(asHtml: false)
      title { romaji english native userPreferred }
      coverImage { extraLarge large medium }
      startDate { year month day }
      nextAiringEpisode { episode airingAt }
      studios(isMain: true) { nodes { id name } }
      relations { edges { relationType node { id type } } }
    }
  }
}
"""

    /** One activity (a notification points to it), with messages too. */
    const val ACTIVITY = """
query (${'$'}id: Int) {
  Activity(id: ${'$'}id) {
    $SOCIAL_ACTIVITY
      ... on MessageActivity {
        id type message(asHtml: false) createdAt likeCount replyCount isLiked
        messenger { id name avatar { large medium } }
      }
  }
}
"""
}

