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
    mediaListEntry { progress progressVolumes status score(format: POINT_10_DECIMAL) repeat }
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
        updatedAt
        status
        progress
        score(format: POINT_10_DECIMAL)
        repeat
        media {
          id
          type
          title { romaji english }
          coverImage { extraLarge large medium }
          episodes
          duration
          format
          averageScore
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
        updatedAt
        status
        progress
        progressVolumes
        score(format: POINT_10_DECIMAL)
        repeat
        media {
          id
          type
          title { romaji english }
          coverImage { extraLarge large medium }
          chapters
          volumes
          format
          averageScore
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
mutation (${'$'}mediaId: Int, ${'$'}status: MediaListStatus, ${'$'}progress: Int, ${'$'}progressVolumes: Int, ${'$'}scoreRaw: Int, ${'$'}repeat: Int) {
  SaveMediaListEntry(mediaId: ${'$'}mediaId, status: ${'$'}status, progress: ${'$'}progress, progressVolumes: ${'$'}progressVolumes, scoreRaw: ${'$'}scoreRaw, repeat: ${'$'}repeat) {
    id
    status
    progress
    progressVolumes
    repeat
  }
}
"""
}
