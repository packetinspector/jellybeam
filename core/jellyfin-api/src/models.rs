#![allow(clippy::redundant_closure_call)]
#![allow(clippy::needless_lifetimes)]
#![allow(clippy::match_single_binding)]
#![allow(clippy::clone_on_copy)]

#[doc = r" Error types."]
pub mod error {
    #[doc = r" Error from a `TryFrom` or `FromStr` implementation."]
    pub struct ConversionError(::std::borrow::Cow<'static, str>);
    impl ::std::error::Error for ConversionError {}
    impl ::std::fmt::Display for ConversionError {
        fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> Result<(), ::std::fmt::Error> {
            ::std::fmt::Display::fmt(&self.0, f)
        }
    }
    impl ::std::fmt::Debug for ConversionError {
        fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> Result<(), ::std::fmt::Error> {
            ::std::fmt::Debug::fmt(&self.0, f)
        }
    }
    impl From<&'static str> for ConversionError {
        fn from(value: &'static str) -> Self {
            Self(value.into())
        }
    }
    impl From<String> for ConversionError {
        fn from(value: String) -> Self {
            Self(value.into())
        }
    }
}
#[doc = "An entity representing a user's access schedule."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"An entity representing a user's access schedule.\","]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"properties\": {"]
#[doc = "    \"DayOfWeek\": {"]
#[doc = "      \"description\": \"Gets or sets the day of week.\","]
#[doc = "      \"enum\": ["]
#[doc = "        \"Sunday\","]
#[doc = "        \"Monday\","]
#[doc = "        \"Tuesday\","]
#[doc = "        \"Wednesday\","]
#[doc = "        \"Thursday\","]
#[doc = "        \"Friday\","]
#[doc = "        \"Saturday\","]
#[doc = "        \"Everyday\","]
#[doc = "        \"Weekday\","]
#[doc = "        \"Weekend\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/DynamicDayOfWeek\""]
#[doc = "        }"]
#[doc = "      ]"]
#[doc = "    },"]
#[doc = "    \"EndHour\": {"]
#[doc = "      \"description\": \"Gets or sets the end hour.\","]
#[doc = "      \"type\": \"number\","]
#[doc = "      \"format\": \"double\""]
#[doc = "    },"]
#[doc = "    \"Id\": {"]
#[doc = "      \"description\": \"Gets the id of this instance.\","]
#[doc = "      \"readOnly\": true,"]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\""]
#[doc = "    },"]
#[doc = "    \"StartHour\": {"]
#[doc = "      \"description\": \"Gets or sets the start hour.\","]
#[doc = "      \"type\": \"number\","]
#[doc = "      \"format\": \"double\""]
#[doc = "    },"]
#[doc = "    \"UserId\": {"]
#[doc = "      \"description\": \"Gets the id of the associated user.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"format\": \"uuid\""]
#[doc = "    }"]
#[doc = "  }"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug, Default)]
pub struct AccessSchedule {
    #[serde(
        rename = "DayOfWeek",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub day_of_week: ::std::option::Option<DynamicDayOfWeek>,
    #[doc = "Gets or sets the end hour."]
    #[serde(
        rename = "EndHour",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub end_hour: ::std::option::Option<f64>,
    #[doc = "Gets the id of this instance."]
    #[serde(
        rename = "Id",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub id: ::std::option::Option<i32>,
    #[doc = "Gets or sets the start hour."]
    #[serde(
        rename = "StartHour",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub start_hour: ::std::option::Option<f64>,
    #[doc = "Gets the id of the associated user."]
    #[serde(
        rename = "UserId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub user_id: ::std::option::Option<::uuid::Uuid>,
}
#[doc = "An enum representing formats of spatial audio."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"An enum representing formats of spatial audio.\","]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"None\","]
#[doc = "    \"DolbyAtmos\","]
#[doc = "    \"DTSX\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum AudioSpatialFormat {
    None,
    DolbyAtmos,
    #[serde(rename = "DTSX")]
    Dtsx,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for AudioSpatialFormat {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::None => f.write_str("None"),
            Self::DolbyAtmos => f.write_str("DolbyAtmos"),
            Self::Dtsx => f.write_str("DTSX"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for AudioSpatialFormat {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "None" => Ok(Self::None),
            "DolbyAtmos" => Ok(Self::DolbyAtmos),
            "DTSX" => Ok(Self::Dtsx),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for AudioSpatialFormat {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for AudioSpatialFormat {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for AudioSpatialFormat {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "The authenticate user by name request body."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"The authenticate user by name request body.\","]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"properties\": {"]
#[doc = "    \"Pw\": {"]
#[doc = "      \"description\": \"Gets or sets the plain text password.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Username\": {"]
#[doc = "      \"description\": \"Gets or sets the username.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    }"]
#[doc = "  }"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug, Default)]
pub struct AuthenticateUserByName {
    #[doc = "Gets or sets the plain text password."]
    #[serde(
        rename = "Pw",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub pw: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the username."]
    #[serde(
        rename = "Username",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub username: ::std::option::Option<::std::string::String>,
}
#[doc = "A class representing an authentication result."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"A class representing an authentication result.\","]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"properties\": {"]
#[doc = "    \"AccessToken\": {"]
#[doc = "      \"description\": \"Gets or sets the access token.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ServerId\": {"]
#[doc = "      \"description\": \"Gets or sets the server id.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"SessionInfo\": {"]
#[doc = "      \"description\": \"Gets or sets the session info.\","]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/SessionInfoDto\""]
#[doc = "        }"]
#[doc = "      ],"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"User\": {"]
#[doc = "      \"description\": \"Gets or sets the user.\","]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/UserDto\""]
#[doc = "        }"]
#[doc = "      ],"]
#[doc = "      \"nullable\": true"]
#[doc = "    }"]
#[doc = "  }"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug, Default)]
pub struct AuthenticationResult {
    #[doc = "Gets or sets the access token."]
    #[serde(
        rename = "AccessToken",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub access_token: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the server id."]
    #[serde(
        rename = "ServerId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub server_id: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the session info."]
    #[serde(
        rename = "SessionInfo",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub session_info: ::std::option::Option<SessionInfoDto>,
    #[doc = "Gets or sets the user."]
    #[serde(
        rename = "User",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub user: ::std::option::Option<UserDto>,
}
#[doc = "This is strictly used as a data transfer object from the api layer.\nThis holds information about a BaseItem in a format that is convenient for the client."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"This is strictly used as a data transfer object from the api layer.\\nThis holds information about a BaseItem in a format that is convenient for the client.\","]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"properties\": {"]
#[doc = "    \"AirDays\": {"]
#[doc = "      \"description\": \"Gets or sets the air days.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"$ref\": \"#/$defs/DayOfWeek\""]
#[doc = "      },"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"AirTime\": {"]
#[doc = "      \"description\": \"Gets or sets the air time.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"AirsAfterSeasonNumber\": {"]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"AirsBeforeEpisodeNumber\": {"]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"AirsBeforeSeasonNumber\": {"]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Album\": {"]
#[doc = "      \"description\": \"Gets or sets the album.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"AlbumArtist\": {"]
#[doc = "      \"description\": \"Gets or sets the album artist.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"AlbumArtists\": {"]
#[doc = "      \"description\": \"Gets or sets the album artists.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"$ref\": \"#/$defs/NameGuidPair\""]
#[doc = "      },"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"AlbumCount\": {"]
#[doc = "      \"description\": \"Gets or sets the album count.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"AlbumId\": {"]
#[doc = "      \"description\": \"Gets or sets the album id.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"format\": \"uuid\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"AlbumNormalizationGain\": {"]
#[doc = "      \"description\": \"Gets or sets the gain required for audio normalization. This field is inherited from music album normalization gain.\","]
#[doc = "      \"type\": \"number\","]
#[doc = "      \"format\": \"float\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"AlbumPrimaryImageTag\": {"]
#[doc = "      \"description\": \"Gets or sets the album image tag.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Altitude\": {"]
#[doc = "      \"type\": \"number\","]
#[doc = "      \"format\": \"double\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Aperture\": {"]
#[doc = "      \"type\": \"number\","]
#[doc = "      \"format\": \"double\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ArtistCount\": {"]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ArtistItems\": {"]
#[doc = "      \"description\": \"Gets or sets the artist items.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"$ref\": \"#/$defs/NameGuidPair\""]
#[doc = "      },"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Artists\": {"]
#[doc = "      \"description\": \"Gets or sets the artists.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"type\": \"string\""]
#[doc = "      },"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"AspectRatio\": {"]
#[doc = "      \"description\": \"Gets or sets the aspect ratio.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Audio\": {"]
#[doc = "      \"description\": \"Gets or sets the audio.\","]
#[doc = "      \"enum\": ["]
#[doc = "        \"Mono\","]
#[doc = "        \"Stereo\","]
#[doc = "        \"Dolby\","]
#[doc = "        \"DolbyDigital\","]
#[doc = "        \"Thx\","]
#[doc = "        \"Atmos\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/ProgramAudio\""]
#[doc = "        }"]
#[doc = "      ],"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"BackdropImageTags\": {"]
#[doc = "      \"description\": \"Gets or sets the backdrop image tags.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"type\": \"string\""]
#[doc = "      },"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"CameraMake\": {"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"CameraModel\": {"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"CanDelete\": {"]
#[doc = "      \"type\": \"boolean\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"CanDownload\": {"]
#[doc = "      \"type\": \"boolean\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ChannelId\": {"]
#[doc = "      \"description\": \"Gets or sets the channel identifier.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"format\": \"uuid\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ChannelName\": {"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ChannelNumber\": {"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ChannelPrimaryImageTag\": {"]
#[doc = "      \"description\": \"Gets or sets the channel primary image tag.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ChannelType\": {"]
#[doc = "      \"description\": \"Gets or sets the type of the channel.\","]
#[doc = "      \"enum\": ["]
#[doc = "        \"TV\","]
#[doc = "        \"Radio\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/ChannelType\""]
#[doc = "        }"]
#[doc = "      ],"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Chapters\": {"]
#[doc = "      \"description\": \"Gets or sets the chapters.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"$ref\": \"#/$defs/ChapterInfo\""]
#[doc = "      },"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ChildCount\": {"]
#[doc = "      \"description\": \"Gets or sets the child count.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"CollectionType\": {"]
#[doc = "      \"description\": \"Gets or sets the type of the collection.\","]
#[doc = "      \"enum\": ["]
#[doc = "        \"unknown\","]
#[doc = "        \"movies\","]
#[doc = "        \"tvshows\","]
#[doc = "        \"music\","]
#[doc = "        \"musicvideos\","]
#[doc = "        \"trailers\","]
#[doc = "        \"homevideos\","]
#[doc = "        \"boxsets\","]
#[doc = "        \"books\","]
#[doc = "        \"photos\","]
#[doc = "        \"livetv\","]
#[doc = "        \"playlists\","]
#[doc = "        \"folders\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/CollectionType\""]
#[doc = "        }"]
#[doc = "      ],"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"CommunityRating\": {"]
#[doc = "      \"description\": \"Gets or sets the community rating.\","]
#[doc = "      \"type\": \"number\","]
#[doc = "      \"format\": \"float\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"CompletionPercentage\": {"]
#[doc = "      \"description\": \"Gets or sets the completion percentage.\","]
#[doc = "      \"type\": \"number\","]
#[doc = "      \"format\": \"double\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Container\": {"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"CriticRating\": {"]
#[doc = "      \"description\": \"Gets or sets the critic rating.\","]
#[doc = "      \"type\": \"number\","]
#[doc = "      \"format\": \"float\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"CumulativeRunTimeTicks\": {"]
#[doc = "      \"description\": \"Gets or sets the cumulative run time ticks.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int64\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"CurrentProgram\": {"]
#[doc = "      \"description\": \"Gets or sets the current program.\","]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/BaseItemDto\""]
#[doc = "        }"]
#[doc = "      ],"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"CustomRating\": {"]
#[doc = "      \"description\": \"Gets or sets the custom rating.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"DateCreated\": {"]
#[doc = "      \"description\": \"Gets or sets the date created.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"format\": \"date-time\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"DateLastMediaAdded\": {"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"format\": \"date-time\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"DisplayOrder\": {"]
#[doc = "      \"description\": \"Gets or sets the display order.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"DisplayPreferencesId\": {"]
#[doc = "      \"description\": \"Gets or sets the display preferences id.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"EnableMediaSourceDisplay\": {"]
#[doc = "      \"type\": \"boolean\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"EndDate\": {"]
#[doc = "      \"description\": \"Gets or sets the end date.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"format\": \"date-time\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"EpisodeCount\": {"]
#[doc = "      \"description\": \"Gets or sets the episode count.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"EpisodeTitle\": {"]
#[doc = "      \"description\": \"Gets or sets the episode title.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Etag\": {"]
#[doc = "      \"description\": \"Gets or sets the etag.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ExposureTime\": {"]
#[doc = "      \"type\": \"number\","]
#[doc = "      \"format\": \"double\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ExternalUrls\": {"]
#[doc = "      \"description\": \"Gets or sets the external urls.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"$ref\": \"#/$defs/ExternalUrl\""]
#[doc = "      },"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ExtraType\": {"]
#[doc = "      \"enum\": ["]
#[doc = "        \"Unknown\","]
#[doc = "        \"Clip\","]
#[doc = "        \"Trailer\","]
#[doc = "        \"BehindTheScenes\","]
#[doc = "        \"DeletedScene\","]
#[doc = "        \"Interview\","]
#[doc = "        \"Scene\","]
#[doc = "        \"Sample\","]
#[doc = "        \"ThemeSong\","]
#[doc = "        \"ThemeVideo\","]
#[doc = "        \"Featurette\","]
#[doc = "        \"Short\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/ExtraType\""]
#[doc = "        }"]
#[doc = "      ],"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"FocalLength\": {"]
#[doc = "      \"type\": \"number\","]
#[doc = "      \"format\": \"double\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ForcedSortName\": {"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"GenreItems\": {"]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"$ref\": \"#/$defs/NameGuidPair\""]
#[doc = "      },"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Genres\": {"]
#[doc = "      \"description\": \"Gets or sets the genres.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"type\": \"string\""]
#[doc = "      },"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"HasLyrics\": {"]
#[doc = "      \"type\": \"boolean\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"HasSubtitles\": {"]
#[doc = "      \"type\": \"boolean\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Height\": {"]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Id\": {"]
#[doc = "      \"description\": \"Gets or sets the id.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"format\": \"uuid\""]
#[doc = "    },"]
#[doc = "    \"ImageBlurHashes\": {"]
#[doc = "      \"$ref\": \"#/$defs/ImageBlurHashes\""]
#[doc = "    },"]
#[doc = "    \"ImageOrientation\": {"]
#[doc = "      \"enum\": ["]
#[doc = "        \"TopLeft\","]
#[doc = "        \"TopRight\","]
#[doc = "        \"BottomRight\","]
#[doc = "        \"BottomLeft\","]
#[doc = "        \"LeftTop\","]
#[doc = "        \"RightTop\","]
#[doc = "        \"RightBottom\","]
#[doc = "        \"LeftBottom\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/ImageOrientation\""]
#[doc = "        }"]
#[doc = "      ],"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ImageTags\": {"]
#[doc = "      \"description\": \"Gets or sets the image tags.\","]
#[doc = "      \"type\": \"object\","]
#[doc = "      \"additionalProperties\": {"]
#[doc = "        \"type\": \"string\","]
#[doc = "        \"nullable\": true"]
#[doc = "      },"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"IndexNumber\": {"]
#[doc = "      \"description\": \"Gets or sets the index number.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"IndexNumberEnd\": {"]
#[doc = "      \"description\": \"Gets or sets the index number end.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"IsFolder\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether this instance is folder.\","]
#[doc = "      \"type\": \"boolean\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"IsHD\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether this instance is HD.\","]
#[doc = "      \"type\": \"boolean\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"IsKids\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether this instance is kids.\","]
#[doc = "      \"type\": \"boolean\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"IsLive\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether this instance is live.\","]
#[doc = "      \"type\": \"boolean\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"IsMovie\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether this instance is movie.\","]
#[doc = "      \"type\": \"boolean\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"IsNews\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether this instance is news.\","]
#[doc = "      \"type\": \"boolean\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"IsPlaceHolder\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether this instance is place holder.\","]
#[doc = "      \"type\": \"boolean\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"IsPremiere\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether this instance is premiere.\","]
#[doc = "      \"type\": \"boolean\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"IsRepeat\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether this instance is repeat.\","]
#[doc = "      \"type\": \"boolean\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"IsSeries\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether this instance is series.\","]
#[doc = "      \"type\": \"boolean\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"IsSports\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether this instance is sports.\","]
#[doc = "      \"type\": \"boolean\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"IsoSpeedRating\": {"]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"IsoType\": {"]
#[doc = "      \"description\": \"Gets or sets the type of the iso.\","]
#[doc = "      \"enum\": ["]
#[doc = "        \"Dvd\","]
#[doc = "        \"BluRay\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/IsoType\""]
#[doc = "        }"]
#[doc = "      ],"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Latitude\": {"]
#[doc = "      \"type\": \"number\","]
#[doc = "      \"format\": \"double\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"LocalTrailerCount\": {"]
#[doc = "      \"description\": \"Gets or sets the local trailer count.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"LocationType\": {"]
#[doc = "      \"description\": \"Gets or sets the type of the location.\","]
#[doc = "      \"enum\": ["]
#[doc = "        \"FileSystem\","]
#[doc = "        \"Remote\","]
#[doc = "        \"Virtual\","]
#[doc = "        \"Offline\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/LocationType\""]
#[doc = "        }"]
#[doc = "      ],"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"LockData\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether [enable internet providers].\","]
#[doc = "      \"type\": \"boolean\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"LockedFields\": {"]
#[doc = "      \"description\": \"Gets or sets the locked fields.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"$ref\": \"#/$defs/MetadataField\""]
#[doc = "      },"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Longitude\": {"]
#[doc = "      \"type\": \"number\","]
#[doc = "      \"format\": \"double\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"MediaSourceCount\": {"]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"MediaSources\": {"]
#[doc = "      \"description\": \"Gets or sets the media versions.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"$ref\": \"#/$defs/MediaSourceInfo\""]
#[doc = "      },"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"MediaStreams\": {"]
#[doc = "      \"description\": \"Gets or sets the media streams.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"$ref\": \"#/$defs/MediaStream\""]
#[doc = "      },"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"MediaType\": {"]
#[doc = "      \"description\": \"Gets or sets the type of the media.\","]
#[doc = "      \"default\": \"Unknown\","]
#[doc = "      \"enum\": ["]
#[doc = "        \"Unknown\","]
#[doc = "        \"Video\","]
#[doc = "        \"Audio\","]
#[doc = "        \"Photo\","]
#[doc = "        \"Book\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/MediaType\""]
#[doc = "        }"]
#[doc = "      ]"]
#[doc = "    },"]
#[doc = "    \"MovieCount\": {"]
#[doc = "      \"description\": \"Gets or sets the movie count.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"MusicVideoCount\": {"]
#[doc = "      \"description\": \"Gets or sets the music video count.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Name\": {"]
#[doc = "      \"description\": \"Gets or sets the name.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"NormalizationGain\": {"]
#[doc = "      \"description\": \"Gets or sets the gain required for audio normalization.\","]
#[doc = "      \"type\": \"number\","]
#[doc = "      \"format\": \"float\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Number\": {"]
#[doc = "      \"description\": \"Gets or sets the number.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"OfficialRating\": {"]
#[doc = "      \"description\": \"Gets or sets the official rating.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"OriginalLanguage\": {"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"OriginalTitle\": {"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Overview\": {"]
#[doc = "      \"description\": \"Gets or sets the overview.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ParentArtImageTag\": {"]
#[doc = "      \"description\": \"Gets or sets the parent art image tag.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ParentArtItemId\": {"]
#[doc = "      \"description\": \"Gets or sets whether the item has fan art, this will hold the Id of the Parent that has one.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"format\": \"uuid\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ParentBackdropImageTags\": {"]
#[doc = "      \"description\": \"Gets or sets the parent backdrop image tags.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"type\": \"string\""]
#[doc = "      },"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ParentBackdropItemId\": {"]
#[doc = "      \"description\": \"Gets or sets whether the item has any backdrops, this will hold the Id of the Parent that has one.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"format\": \"uuid\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ParentId\": {"]
#[doc = "      \"description\": \"Gets or sets the parent id.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"format\": \"uuid\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ParentIndexNumber\": {"]
#[doc = "      \"description\": \"Gets or sets the parent index number.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ParentLogoImageTag\": {"]
#[doc = "      \"description\": \"Gets or sets the parent logo image tag.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ParentLogoItemId\": {"]
#[doc = "      \"description\": \"Gets or sets whether the item has a logo, this will hold the Id of the Parent that has one.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"format\": \"uuid\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ParentPrimaryImageItemId\": {"]
#[doc = "      \"description\": \"Gets or sets the parent primary image item identifier.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"format\": \"uuid\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ParentPrimaryImageTag\": {"]
#[doc = "      \"description\": \"Gets or sets the parent primary image tag.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ParentThumbImageTag\": {"]
#[doc = "      \"description\": \"Gets or sets the parent thumb image tag.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ParentThumbItemId\": {"]
#[doc = "      \"description\": \"Gets or sets the parent thumb item id.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"format\": \"uuid\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"PartCount\": {"]
#[doc = "      \"description\": \"Gets or sets the part count.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Path\": {"]
#[doc = "      \"description\": \"Gets or sets the path.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"People\": {"]
#[doc = "      \"description\": \"Gets or sets the people.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"$ref\": \"#/$defs/BaseItemPerson\""]
#[doc = "      },"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"PlayAccess\": {"]
#[doc = "      \"description\": \"Gets or sets the play access.\","]
#[doc = "      \"enum\": ["]
#[doc = "        \"Full\","]
#[doc = "        \"None\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/PlayAccess\""]
#[doc = "        }"]
#[doc = "      ],"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"PlaylistItemId\": {"]
#[doc = "      \"description\": \"Gets or sets the playlist item identifier.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"PreferredMetadataCountryCode\": {"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"PreferredMetadataLanguage\": {"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"PremiereDate\": {"]
#[doc = "      \"description\": \"Gets or sets the premiere date.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"format\": \"date-time\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"PrimaryImageAspectRatio\": {"]
#[doc = "      \"description\": \"Gets or sets the primary image aspect ratio, after image enhancements.\","]
#[doc = "      \"type\": \"number\","]
#[doc = "      \"format\": \"double\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ProductionLocations\": {"]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"type\": \"string\""]
#[doc = "      },"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ProductionYear\": {"]
#[doc = "      \"description\": \"Gets or sets the production year.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ProgramCount\": {"]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ProgramId\": {"]
#[doc = "      \"description\": \"Gets or sets the program identifier.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ProviderIds\": {"]
#[doc = "      \"description\": \"Gets or sets the provider ids.\","]
#[doc = "      \"type\": \"object\","]
#[doc = "      \"additionalProperties\": {"]
#[doc = "        \"type\": \"string\","]
#[doc = "        \"nullable\": true"]
#[doc = "      },"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"RecursiveItemCount\": {"]
#[doc = "      \"description\": \"Gets or sets the recursive item count.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"RemoteTrailers\": {"]
#[doc = "      \"description\": \"Gets or sets the trailer urls.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"$ref\": \"#/$defs/MediaUrl\""]
#[doc = "      },"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"RunTimeTicks\": {"]
#[doc = "      \"description\": \"Gets or sets the run time ticks.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int64\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ScreenshotImageTags\": {"]
#[doc = "      \"description\": \"Gets or sets the screenshot image tags.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"type\": \"string\""]
#[doc = "      },"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"SeasonId\": {"]
#[doc = "      \"description\": \"Gets or sets the season identifier.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"format\": \"uuid\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"SeasonName\": {"]
#[doc = "      \"description\": \"Gets or sets the name of the season.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"SeriesCount\": {"]
#[doc = "      \"description\": \"Gets or sets the series count.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"SeriesId\": {"]
#[doc = "      \"description\": \"Gets or sets the series id.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"format\": \"uuid\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"SeriesName\": {"]
#[doc = "      \"description\": \"Gets or sets the name of the series.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"SeriesPrimaryImageTag\": {"]
#[doc = "      \"description\": \"Gets or sets the series primary image tag.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"SeriesStudio\": {"]
#[doc = "      \"description\": \"Gets or sets the series studio.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"SeriesThumbImageTag\": {"]
#[doc = "      \"description\": \"Gets or sets the series thumb image tag.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"SeriesTimerId\": {"]
#[doc = "      \"description\": \"Gets or sets the series timer identifier.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ServerId\": {"]
#[doc = "      \"description\": \"Gets or sets the server identifier.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ShutterSpeed\": {"]
#[doc = "      \"type\": \"number\","]
#[doc = "      \"format\": \"double\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Software\": {"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"SongCount\": {"]
#[doc = "      \"description\": \"Gets or sets the song count.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"SortName\": {"]
#[doc = "      \"description\": \"Gets or sets the name of the sort.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"SourceType\": {"]
#[doc = "      \"description\": \"Gets or sets the type of the source.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"SpecialFeatureCount\": {"]
#[doc = "      \"description\": \"Gets or sets the special feature count.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"StartDate\": {"]
#[doc = "      \"description\": \"Gets or sets the start date of the recording, in UTC.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"format\": \"date-time\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Status\": {"]
#[doc = "      \"description\": \"Gets or sets the status.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Studios\": {"]
#[doc = "      \"description\": \"Gets or sets the studios.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"$ref\": \"#/$defs/NameGuidPair\""]
#[doc = "      },"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Taglines\": {"]
#[doc = "      \"description\": \"Gets or sets the taglines.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"type\": \"string\""]
#[doc = "      },"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Tags\": {"]
#[doc = "      \"description\": \"Gets or sets the tags.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"type\": \"string\""]
#[doc = "      },"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"TimerId\": {"]
#[doc = "      \"description\": \"Gets or sets the timer identifier.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"TrailerCount\": {"]
#[doc = "      \"description\": \"Gets or sets the trailer count.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Trickplay\": {"]
#[doc = "      \"description\": \"Gets or sets the trickplay manifest.\","]
#[doc = "      \"type\": \"object\","]
#[doc = "      \"additionalProperties\": {"]
#[doc = "        \"type\": \"object\","]
#[doc = "        \"additionalProperties\": {"]
#[doc = "          \"$ref\": \"#/$defs/TrickplayInfoDto\""]
#[doc = "        },"]
#[doc = "        \"nullable\": true"]
#[doc = "      },"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Type\": {"]
#[doc = "      \"description\": \"Gets or sets the type.\","]
#[doc = "      \"enum\": ["]
#[doc = "        \"AggregateFolder\","]
#[doc = "        \"Audio\","]
#[doc = "        \"AudioBook\","]
#[doc = "        \"BasePluginFolder\","]
#[doc = "        \"Book\","]
#[doc = "        \"BoxSet\","]
#[doc = "        \"Channel\","]
#[doc = "        \"ChannelFolderItem\","]
#[doc = "        \"CollectionFolder\","]
#[doc = "        \"Episode\","]
#[doc = "        \"Folder\","]
#[doc = "        \"Genre\","]
#[doc = "        \"ManualPlaylistsFolder\","]
#[doc = "        \"Movie\","]
#[doc = "        \"LiveTvChannel\","]
#[doc = "        \"LiveTvProgram\","]
#[doc = "        \"MusicAlbum\","]
#[doc = "        \"MusicArtist\","]
#[doc = "        \"MusicGenre\","]
#[doc = "        \"MusicVideo\","]
#[doc = "        \"Person\","]
#[doc = "        \"Photo\","]
#[doc = "        \"PhotoAlbum\","]
#[doc = "        \"Playlist\","]
#[doc = "        \"PlaylistsFolder\","]
#[doc = "        \"Program\","]
#[doc = "        \"Recording\","]
#[doc = "        \"Season\","]
#[doc = "        \"Series\","]
#[doc = "        \"Studio\","]
#[doc = "        \"Trailer\","]
#[doc = "        \"TvChannel\","]
#[doc = "        \"TvProgram\","]
#[doc = "        \"UserRootFolder\","]
#[doc = "        \"UserView\","]
#[doc = "        \"Video\","]
#[doc = "        \"Year\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/BaseItemKind\""]
#[doc = "        }"]
#[doc = "      ]"]
#[doc = "    },"]
#[doc = "    \"UserData\": {"]
#[doc = "      \"description\": \"Gets or sets the user data for this item based on the user it's being requested for.\","]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/UserItemDataDto\""]
#[doc = "        }"]
#[doc = "      ],"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Video3DFormat\": {"]
#[doc = "      \"description\": \"Gets or sets the video3 D format.\","]
#[doc = "      \"enum\": ["]
#[doc = "        \"HalfSideBySide\","]
#[doc = "        \"FullSideBySide\","]
#[doc = "        \"FullTopAndBottom\","]
#[doc = "        \"HalfTopAndBottom\","]
#[doc = "        \"MVC\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/Video3DFormat\""]
#[doc = "        }"]
#[doc = "      ],"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"VideoType\": {"]
#[doc = "      \"description\": \"Gets or sets the type of the video.\","]
#[doc = "      \"enum\": ["]
#[doc = "        \"VideoFile\","]
#[doc = "        \"Iso\","]
#[doc = "        \"Dvd\","]
#[doc = "        \"BluRay\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/VideoType\""]
#[doc = "        }"]
#[doc = "      ],"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Width\": {"]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    }"]
#[doc = "  }"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug, Default)]
pub struct BaseItemDto {
    #[doc = "Gets or sets the air days."]
    #[serde(
        rename = "AirDays",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub air_days: ::std::vec::Vec<DayOfWeek>,
    #[doc = "Gets or sets the air time."]
    #[serde(
        rename = "AirTime",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub air_time: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "AirsAfterSeasonNumber",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub airs_after_season_number: ::std::option::Option<i32>,
    #[serde(
        rename = "AirsBeforeEpisodeNumber",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub airs_before_episode_number: ::std::option::Option<i32>,
    #[serde(
        rename = "AirsBeforeSeasonNumber",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub airs_before_season_number: ::std::option::Option<i32>,
    #[doc = "Gets or sets the album."]
    #[serde(
        rename = "Album",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub album: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the album artist."]
    #[serde(
        rename = "AlbumArtist",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub album_artist: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the album artists."]
    #[serde(
        rename = "AlbumArtists",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub album_artists: ::std::vec::Vec<NameGuidPair>,
    #[doc = "Gets or sets the album count."]
    #[serde(
        rename = "AlbumCount",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub album_count: ::std::option::Option<i32>,
    #[doc = "Gets or sets the album id."]
    #[serde(
        rename = "AlbumId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub album_id: ::std::option::Option<::uuid::Uuid>,
    #[doc = "Gets or sets the gain required for audio normalization. This field is inherited from music album normalization gain."]
    #[serde(
        rename = "AlbumNormalizationGain",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub album_normalization_gain: ::std::option::Option<f32>,
    #[doc = "Gets or sets the album image tag."]
    #[serde(
        rename = "AlbumPrimaryImageTag",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub album_primary_image_tag: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "Altitude",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub altitude: ::std::option::Option<f64>,
    #[serde(
        rename = "Aperture",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub aperture: ::std::option::Option<f64>,
    #[serde(
        rename = "ArtistCount",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub artist_count: ::std::option::Option<i32>,
    #[doc = "Gets or sets the artist items."]
    #[serde(
        rename = "ArtistItems",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub artist_items: ::std::vec::Vec<NameGuidPair>,
    #[doc = "Gets or sets the artists."]
    #[serde(
        rename = "Artists",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub artists: ::std::vec::Vec<::std::string::String>,
    #[doc = "Gets or sets the aspect ratio."]
    #[serde(
        rename = "AspectRatio",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub aspect_ratio: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "Audio",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub audio: ::std::option::Option<ProgramAudio>,
    #[doc = "Gets or sets the backdrop image tags."]
    #[serde(
        rename = "BackdropImageTags",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub backdrop_image_tags: ::std::vec::Vec<::std::string::String>,
    #[serde(
        rename = "CameraMake",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub camera_make: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "CameraModel",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub camera_model: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "CanDelete",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub can_delete: ::std::option::Option<bool>,
    #[serde(
        rename = "CanDownload",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub can_download: ::std::option::Option<bool>,
    #[doc = "Gets or sets the channel identifier."]
    #[serde(
        rename = "ChannelId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub channel_id: ::std::option::Option<::uuid::Uuid>,
    #[serde(
        rename = "ChannelName",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub channel_name: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "ChannelNumber",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub channel_number: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the channel primary image tag."]
    #[serde(
        rename = "ChannelPrimaryImageTag",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub channel_primary_image_tag: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "ChannelType",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub channel_type: ::std::option::Option<ChannelType>,
    #[doc = "Gets or sets the chapters."]
    #[serde(
        rename = "Chapters",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub chapters: ::std::vec::Vec<ChapterInfo>,
    #[doc = "Gets or sets the child count."]
    #[serde(
        rename = "ChildCount",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub child_count: ::std::option::Option<i32>,
    #[serde(
        rename = "CollectionType",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub collection_type: ::std::option::Option<CollectionType>,
    #[doc = "Gets or sets the community rating."]
    #[serde(
        rename = "CommunityRating",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub community_rating: ::std::option::Option<f32>,
    #[doc = "Gets or sets the completion percentage."]
    #[serde(
        rename = "CompletionPercentage",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub completion_percentage: ::std::option::Option<f64>,
    #[serde(
        rename = "Container",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub container: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the critic rating."]
    #[serde(
        rename = "CriticRating",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub critic_rating: ::std::option::Option<f32>,
    #[doc = "Gets or sets the cumulative run time ticks."]
    #[serde(
        rename = "CumulativeRunTimeTicks",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub cumulative_run_time_ticks: ::std::option::Option<i64>,
    #[doc = "Gets or sets the current program."]
    #[serde(rename = "CurrentProgram", default)]
    pub current_program: ::std::boxed::Box<::std::option::Option<BaseItemDto>>,
    #[doc = "Gets or sets the custom rating."]
    #[serde(
        rename = "CustomRating",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub custom_rating: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the date created."]
    #[serde(
        rename = "DateCreated",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub date_created: ::std::option::Option<::chrono::DateTime<::chrono::offset::Utc>>,
    #[serde(
        rename = "DateLastMediaAdded",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub date_last_media_added: ::std::option::Option<::chrono::DateTime<::chrono::offset::Utc>>,
    #[doc = "Gets or sets the display order."]
    #[serde(
        rename = "DisplayOrder",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub display_order: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the display preferences id."]
    #[serde(
        rename = "DisplayPreferencesId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub display_preferences_id: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "EnableMediaSourceDisplay",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub enable_media_source_display: ::std::option::Option<bool>,
    #[doc = "Gets or sets the end date."]
    #[serde(
        rename = "EndDate",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub end_date: ::std::option::Option<::chrono::DateTime<::chrono::offset::Utc>>,
    #[doc = "Gets or sets the episode count."]
    #[serde(
        rename = "EpisodeCount",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub episode_count: ::std::option::Option<i32>,
    #[doc = "Gets or sets the episode title."]
    #[serde(
        rename = "EpisodeTitle",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub episode_title: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the etag."]
    #[serde(
        rename = "Etag",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub etag: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "ExposureTime",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub exposure_time: ::std::option::Option<f64>,
    #[doc = "Gets or sets the external urls."]
    #[serde(
        rename = "ExternalUrls",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub external_urls: ::std::vec::Vec<ExternalUrl>,
    #[serde(
        rename = "ExtraType",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub extra_type: ::std::option::Option<ExtraType>,
    #[serde(
        rename = "FocalLength",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub focal_length: ::std::option::Option<f64>,
    #[serde(
        rename = "ForcedSortName",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub forced_sort_name: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "GenreItems",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub genre_items: ::std::vec::Vec<NameGuidPair>,
    #[doc = "Gets or sets the genres."]
    #[serde(
        rename = "Genres",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub genres: ::std::vec::Vec<::std::string::String>,
    #[serde(
        rename = "HasLyrics",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub has_lyrics: ::std::option::Option<bool>,
    #[serde(
        rename = "HasSubtitles",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub has_subtitles: ::std::option::Option<bool>,
    #[serde(
        rename = "Height",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub height: ::std::option::Option<i32>,
    #[doc = "Gets or sets the id."]
    #[serde(
        rename = "Id",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub id: ::std::option::Option<::uuid::Uuid>,
    #[serde(
        rename = "ImageBlurHashes",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub image_blur_hashes: ::std::option::Option<ImageBlurHashes>,
    #[serde(
        rename = "ImageOrientation",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub image_orientation: ::std::option::Option<ImageOrientation>,
    #[doc = "Gets or sets the image tags."]
    #[serde(
        rename = "ImageTags",
        default,
        skip_serializing_if = ":: std :: collections :: HashMap::is_empty"
    )]
    pub image_tags: ::std::collections::HashMap<::std::string::String, ::std::string::String>,
    #[doc = "Gets or sets the index number."]
    #[serde(
        rename = "IndexNumber",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub index_number: ::std::option::Option<i32>,
    #[doc = "Gets or sets the index number end."]
    #[serde(
        rename = "IndexNumberEnd",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub index_number_end: ::std::option::Option<i32>,
    #[doc = "Gets or sets a value indicating whether this instance is folder."]
    #[serde(
        rename = "IsFolder",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub is_folder: ::std::option::Option<bool>,
    #[doc = "Gets or sets a value indicating whether this instance is HD."]
    #[serde(
        rename = "IsHD",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub is_hd: ::std::option::Option<bool>,
    #[doc = "Gets or sets a value indicating whether this instance is kids."]
    #[serde(
        rename = "IsKids",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub is_kids: ::std::option::Option<bool>,
    #[doc = "Gets or sets a value indicating whether this instance is live."]
    #[serde(
        rename = "IsLive",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub is_live: ::std::option::Option<bool>,
    #[doc = "Gets or sets a value indicating whether this instance is movie."]
    #[serde(
        rename = "IsMovie",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub is_movie: ::std::option::Option<bool>,
    #[doc = "Gets or sets a value indicating whether this instance is news."]
    #[serde(
        rename = "IsNews",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub is_news: ::std::option::Option<bool>,
    #[doc = "Gets or sets a value indicating whether this instance is place holder."]
    #[serde(
        rename = "IsPlaceHolder",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub is_place_holder: ::std::option::Option<bool>,
    #[doc = "Gets or sets a value indicating whether this instance is premiere."]
    #[serde(
        rename = "IsPremiere",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub is_premiere: ::std::option::Option<bool>,
    #[doc = "Gets or sets a value indicating whether this instance is repeat."]
    #[serde(
        rename = "IsRepeat",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub is_repeat: ::std::option::Option<bool>,
    #[doc = "Gets or sets a value indicating whether this instance is series."]
    #[serde(
        rename = "IsSeries",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub is_series: ::std::option::Option<bool>,
    #[doc = "Gets or sets a value indicating whether this instance is sports."]
    #[serde(
        rename = "IsSports",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub is_sports: ::std::option::Option<bool>,
    #[serde(
        rename = "IsoSpeedRating",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub iso_speed_rating: ::std::option::Option<i32>,
    #[serde(
        rename = "IsoType",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub iso_type: ::std::option::Option<IsoType>,
    #[serde(
        rename = "Latitude",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub latitude: ::std::option::Option<f64>,
    #[doc = "Gets or sets the local trailer count."]
    #[serde(
        rename = "LocalTrailerCount",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub local_trailer_count: ::std::option::Option<i32>,
    #[serde(
        rename = "LocationType",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub location_type: ::std::option::Option<LocationType>,
    #[doc = "Gets or sets a value indicating whether [enable internet providers]."]
    #[serde(
        rename = "LockData",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub lock_data: ::std::option::Option<bool>,
    #[doc = "Gets or sets the locked fields."]
    #[serde(
        rename = "LockedFields",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub locked_fields: ::std::vec::Vec<MetadataField>,
    #[serde(
        rename = "Longitude",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub longitude: ::std::option::Option<f64>,
    #[serde(
        rename = "MediaSourceCount",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub media_source_count: ::std::option::Option<i32>,
    #[doc = "Gets or sets the media versions."]
    #[serde(
        rename = "MediaSources",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub media_sources: ::std::vec::Vec<MediaSourceInfo>,
    #[doc = "Gets or sets the media streams."]
    #[serde(
        rename = "MediaStreams",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub media_streams: ::std::vec::Vec<MediaStream>,
    #[serde(
        rename = "MediaType",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub media_type: ::std::option::Option<MediaType>,
    #[doc = "Gets or sets the movie count."]
    #[serde(
        rename = "MovieCount",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub movie_count: ::std::option::Option<i32>,
    #[doc = "Gets or sets the music video count."]
    #[serde(
        rename = "MusicVideoCount",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub music_video_count: ::std::option::Option<i32>,
    #[doc = "Gets or sets the name."]
    #[serde(
        rename = "Name",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub name: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the gain required for audio normalization."]
    #[serde(
        rename = "NormalizationGain",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub normalization_gain: ::std::option::Option<f32>,
    #[doc = "Gets or sets the number."]
    #[serde(
        rename = "Number",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub number: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the official rating."]
    #[serde(
        rename = "OfficialRating",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub official_rating: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "OriginalLanguage",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub original_language: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "OriginalTitle",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub original_title: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the overview."]
    #[serde(
        rename = "Overview",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub overview: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the parent art image tag."]
    #[serde(
        rename = "ParentArtImageTag",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub parent_art_image_tag: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets whether the item has fan art, this will hold the Id of the Parent that has one."]
    #[serde(
        rename = "ParentArtItemId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub parent_art_item_id: ::std::option::Option<::uuid::Uuid>,
    #[doc = "Gets or sets the parent backdrop image tags."]
    #[serde(
        rename = "ParentBackdropImageTags",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub parent_backdrop_image_tags: ::std::vec::Vec<::std::string::String>,
    #[doc = "Gets or sets whether the item has any backdrops, this will hold the Id of the Parent that has one."]
    #[serde(
        rename = "ParentBackdropItemId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub parent_backdrop_item_id: ::std::option::Option<::uuid::Uuid>,
    #[doc = "Gets or sets the parent id."]
    #[serde(
        rename = "ParentId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub parent_id: ::std::option::Option<::uuid::Uuid>,
    #[doc = "Gets or sets the parent index number."]
    #[serde(
        rename = "ParentIndexNumber",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub parent_index_number: ::std::option::Option<i32>,
    #[doc = "Gets or sets the parent logo image tag."]
    #[serde(
        rename = "ParentLogoImageTag",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub parent_logo_image_tag: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets whether the item has a logo, this will hold the Id of the Parent that has one."]
    #[serde(
        rename = "ParentLogoItemId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub parent_logo_item_id: ::std::option::Option<::uuid::Uuid>,
    #[doc = "Gets or sets the parent primary image item identifier."]
    #[serde(
        rename = "ParentPrimaryImageItemId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub parent_primary_image_item_id: ::std::option::Option<::uuid::Uuid>,
    #[doc = "Gets or sets the parent primary image tag."]
    #[serde(
        rename = "ParentPrimaryImageTag",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub parent_primary_image_tag: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the parent thumb image tag."]
    #[serde(
        rename = "ParentThumbImageTag",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub parent_thumb_image_tag: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the parent thumb item id."]
    #[serde(
        rename = "ParentThumbItemId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub parent_thumb_item_id: ::std::option::Option<::uuid::Uuid>,
    #[doc = "Gets or sets the part count."]
    #[serde(
        rename = "PartCount",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub part_count: ::std::option::Option<i32>,
    #[doc = "Gets or sets the path."]
    #[serde(
        rename = "Path",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub path: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the people."]
    #[serde(
        rename = "People",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub people: ::std::vec::Vec<BaseItemPerson>,
    #[serde(
        rename = "PlayAccess",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub play_access: ::std::option::Option<PlayAccess>,
    #[doc = "Gets or sets the playlist item identifier."]
    #[serde(
        rename = "PlaylistItemId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub playlist_item_id: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "PreferredMetadataCountryCode",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub preferred_metadata_country_code: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "PreferredMetadataLanguage",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub preferred_metadata_language: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the premiere date."]
    #[serde(
        rename = "PremiereDate",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub premiere_date: ::std::option::Option<::chrono::DateTime<::chrono::offset::Utc>>,
    #[doc = "Gets or sets the primary image aspect ratio, after image enhancements."]
    #[serde(
        rename = "PrimaryImageAspectRatio",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub primary_image_aspect_ratio: ::std::option::Option<f64>,
    #[serde(
        rename = "ProductionLocations",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub production_locations: ::std::vec::Vec<::std::string::String>,
    #[doc = "Gets or sets the production year."]
    #[serde(
        rename = "ProductionYear",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub production_year: ::std::option::Option<i32>,
    #[serde(
        rename = "ProgramCount",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub program_count: ::std::option::Option<i32>,
    #[doc = "Gets or sets the program identifier."]
    #[serde(
        rename = "ProgramId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub program_id: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the provider ids."]
    #[serde(
        rename = "ProviderIds",
        default,
        skip_serializing_if = ":: std :: collections :: HashMap::is_empty"
    )]
    pub provider_ids: ::std::collections::HashMap<::std::string::String, ::std::string::String>,
    #[doc = "Gets or sets the recursive item count."]
    #[serde(
        rename = "RecursiveItemCount",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub recursive_item_count: ::std::option::Option<i32>,
    #[doc = "Gets or sets the trailer urls."]
    #[serde(
        rename = "RemoteTrailers",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub remote_trailers: ::std::vec::Vec<MediaUrl>,
    #[doc = "Gets or sets the run time ticks."]
    #[serde(
        rename = "RunTimeTicks",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub run_time_ticks: ::std::option::Option<i64>,
    #[doc = "Gets or sets the screenshot image tags."]
    #[serde(
        rename = "ScreenshotImageTags",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub screenshot_image_tags: ::std::vec::Vec<::std::string::String>,
    #[doc = "Gets or sets the season identifier."]
    #[serde(
        rename = "SeasonId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub season_id: ::std::option::Option<::uuid::Uuid>,
    #[doc = "Gets or sets the name of the season."]
    #[serde(
        rename = "SeasonName",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub season_name: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the series count."]
    #[serde(
        rename = "SeriesCount",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub series_count: ::std::option::Option<i32>,
    #[doc = "Gets or sets the series id."]
    #[serde(
        rename = "SeriesId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub series_id: ::std::option::Option<::uuid::Uuid>,
    #[doc = "Gets or sets the name of the series."]
    #[serde(
        rename = "SeriesName",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub series_name: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the series primary image tag."]
    #[serde(
        rename = "SeriesPrimaryImageTag",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub series_primary_image_tag: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the series studio."]
    #[serde(
        rename = "SeriesStudio",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub series_studio: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the series thumb image tag."]
    #[serde(
        rename = "SeriesThumbImageTag",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub series_thumb_image_tag: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the series timer identifier."]
    #[serde(
        rename = "SeriesTimerId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub series_timer_id: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the server identifier."]
    #[serde(
        rename = "ServerId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub server_id: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "ShutterSpeed",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub shutter_speed: ::std::option::Option<f64>,
    #[serde(
        rename = "Software",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub software: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the song count."]
    #[serde(
        rename = "SongCount",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub song_count: ::std::option::Option<i32>,
    #[doc = "Gets or sets the name of the sort."]
    #[serde(
        rename = "SortName",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub sort_name: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the type of the source."]
    #[serde(
        rename = "SourceType",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub source_type: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the special feature count."]
    #[serde(
        rename = "SpecialFeatureCount",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub special_feature_count: ::std::option::Option<i32>,
    #[doc = "Gets or sets the start date of the recording, in UTC."]
    #[serde(
        rename = "StartDate",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub start_date: ::std::option::Option<::chrono::DateTime<::chrono::offset::Utc>>,
    #[doc = "Gets or sets the status."]
    #[serde(
        rename = "Status",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub status: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the studios."]
    #[serde(
        rename = "Studios",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub studios: ::std::vec::Vec<NameGuidPair>,
    #[doc = "Gets or sets the taglines."]
    #[serde(
        rename = "Taglines",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub taglines: ::std::vec::Vec<::std::string::String>,
    #[doc = "Gets or sets the tags."]
    #[serde(
        rename = "Tags",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub tags: ::std::vec::Vec<::std::string::String>,
    #[doc = "Gets or sets the timer identifier."]
    #[serde(
        rename = "TimerId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub timer_id: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the trailer count."]
    #[serde(
        rename = "TrailerCount",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub trailer_count: ::std::option::Option<i32>,
    #[doc = "Gets or sets the trickplay manifest."]
    #[serde(
        rename = "Trickplay",
        default,
        skip_serializing_if = ":: std :: collections :: HashMap::is_empty"
    )]
    pub trickplay: ::std::collections::HashMap<
        ::std::string::String,
        ::std::collections::HashMap<::std::string::String, TrickplayInfoDto>,
    >,
    #[serde(
        rename = "Type",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub type_: ::std::option::Option<BaseItemKind>,
    #[doc = "Gets or sets the user data for this item based on the user it's being requested for."]
    #[serde(
        rename = "UserData",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub user_data: ::std::option::Option<UserItemDataDto>,
    #[serde(
        rename = "Video3DFormat",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub video3_d_format: ::std::option::Option<Video3DFormat>,
    #[serde(
        rename = "VideoType",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub video_type: ::std::option::Option<VideoType>,
    #[serde(
        rename = "Width",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub width: ::std::option::Option<i32>,
}
#[doc = "Query result container."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"Query result container.\","]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"properties\": {"]
#[doc = "    \"Items\": {"]
#[doc = "      \"description\": \"Gets or sets the items.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"$ref\": \"#/$defs/BaseItemDto\""]
#[doc = "      }"]
#[doc = "    },"]
#[doc = "    \"StartIndex\": {"]
#[doc = "      \"description\": \"Gets or sets the index of the first record in Items.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\""]
#[doc = "    },"]
#[doc = "    \"TotalRecordCount\": {"]
#[doc = "      \"description\": \"Gets or sets the total number of records available.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\""]
#[doc = "    }"]
#[doc = "  }"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug, Default)]
pub struct BaseItemDtoQueryResult {
    #[doc = "Gets or sets the items."]
    #[serde(
        rename = "Items",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub items: ::std::vec::Vec<BaseItemDto>,
    #[doc = "Gets or sets the index of the first record in Items."]
    #[serde(
        rename = "StartIndex",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub start_index: ::std::option::Option<i32>,
    #[doc = "Gets or sets the total number of records available."]
    #[serde(
        rename = "TotalRecordCount",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub total_record_count: ::std::option::Option<i32>,
}
#[doc = "The base item kind."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"The base item kind.\","]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"AggregateFolder\","]
#[doc = "    \"Audio\","]
#[doc = "    \"AudioBook\","]
#[doc = "    \"BasePluginFolder\","]
#[doc = "    \"Book\","]
#[doc = "    \"BoxSet\","]
#[doc = "    \"Channel\","]
#[doc = "    \"ChannelFolderItem\","]
#[doc = "    \"CollectionFolder\","]
#[doc = "    \"Episode\","]
#[doc = "    \"Folder\","]
#[doc = "    \"Genre\","]
#[doc = "    \"ManualPlaylistsFolder\","]
#[doc = "    \"Movie\","]
#[doc = "    \"LiveTvChannel\","]
#[doc = "    \"LiveTvProgram\","]
#[doc = "    \"MusicAlbum\","]
#[doc = "    \"MusicArtist\","]
#[doc = "    \"MusicGenre\","]
#[doc = "    \"MusicVideo\","]
#[doc = "    \"Person\","]
#[doc = "    \"Photo\","]
#[doc = "    \"PhotoAlbum\","]
#[doc = "    \"Playlist\","]
#[doc = "    \"PlaylistsFolder\","]
#[doc = "    \"Program\","]
#[doc = "    \"Recording\","]
#[doc = "    \"Season\","]
#[doc = "    \"Series\","]
#[doc = "    \"Studio\","]
#[doc = "    \"Trailer\","]
#[doc = "    \"TvChannel\","]
#[doc = "    \"TvProgram\","]
#[doc = "    \"UserRootFolder\","]
#[doc = "    \"UserView\","]
#[doc = "    \"Video\","]
#[doc = "    \"Year\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum BaseItemKind {
    AggregateFolder,
    Audio,
    AudioBook,
    BasePluginFolder,
    Book,
    BoxSet,
    Channel,
    ChannelFolderItem,
    CollectionFolder,
    Episode,
    Folder,
    Genre,
    ManualPlaylistsFolder,
    Movie,
    LiveTvChannel,
    LiveTvProgram,
    MusicAlbum,
    MusicArtist,
    MusicGenre,
    MusicVideo,
    Person,
    Photo,
    PhotoAlbum,
    Playlist,
    PlaylistsFolder,
    Program,
    Recording,
    Season,
    Series,
    Studio,
    Trailer,
    TvChannel,
    TvProgram,
    UserRootFolder,
    UserView,
    Video,
    Year,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for BaseItemKind {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::AggregateFolder => f.write_str("AggregateFolder"),
            Self::Audio => f.write_str("Audio"),
            Self::AudioBook => f.write_str("AudioBook"),
            Self::BasePluginFolder => f.write_str("BasePluginFolder"),
            Self::Book => f.write_str("Book"),
            Self::BoxSet => f.write_str("BoxSet"),
            Self::Channel => f.write_str("Channel"),
            Self::ChannelFolderItem => f.write_str("ChannelFolderItem"),
            Self::CollectionFolder => f.write_str("CollectionFolder"),
            Self::Episode => f.write_str("Episode"),
            Self::Folder => f.write_str("Folder"),
            Self::Genre => f.write_str("Genre"),
            Self::ManualPlaylistsFolder => f.write_str("ManualPlaylistsFolder"),
            Self::Movie => f.write_str("Movie"),
            Self::LiveTvChannel => f.write_str("LiveTvChannel"),
            Self::LiveTvProgram => f.write_str("LiveTvProgram"),
            Self::MusicAlbum => f.write_str("MusicAlbum"),
            Self::MusicArtist => f.write_str("MusicArtist"),
            Self::MusicGenre => f.write_str("MusicGenre"),
            Self::MusicVideo => f.write_str("MusicVideo"),
            Self::Person => f.write_str("Person"),
            Self::Photo => f.write_str("Photo"),
            Self::PhotoAlbum => f.write_str("PhotoAlbum"),
            Self::Playlist => f.write_str("Playlist"),
            Self::PlaylistsFolder => f.write_str("PlaylistsFolder"),
            Self::Program => f.write_str("Program"),
            Self::Recording => f.write_str("Recording"),
            Self::Season => f.write_str("Season"),
            Self::Series => f.write_str("Series"),
            Self::Studio => f.write_str("Studio"),
            Self::Trailer => f.write_str("Trailer"),
            Self::TvChannel => f.write_str("TvChannel"),
            Self::TvProgram => f.write_str("TvProgram"),
            Self::UserRootFolder => f.write_str("UserRootFolder"),
            Self::UserView => f.write_str("UserView"),
            Self::Video => f.write_str("Video"),
            Self::Year => f.write_str("Year"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for BaseItemKind {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "AggregateFolder" => Ok(Self::AggregateFolder),
            "Audio" => Ok(Self::Audio),
            "AudioBook" => Ok(Self::AudioBook),
            "BasePluginFolder" => Ok(Self::BasePluginFolder),
            "Book" => Ok(Self::Book),
            "BoxSet" => Ok(Self::BoxSet),
            "Channel" => Ok(Self::Channel),
            "ChannelFolderItem" => Ok(Self::ChannelFolderItem),
            "CollectionFolder" => Ok(Self::CollectionFolder),
            "Episode" => Ok(Self::Episode),
            "Folder" => Ok(Self::Folder),
            "Genre" => Ok(Self::Genre),
            "ManualPlaylistsFolder" => Ok(Self::ManualPlaylistsFolder),
            "Movie" => Ok(Self::Movie),
            "LiveTvChannel" => Ok(Self::LiveTvChannel),
            "LiveTvProgram" => Ok(Self::LiveTvProgram),
            "MusicAlbum" => Ok(Self::MusicAlbum),
            "MusicArtist" => Ok(Self::MusicArtist),
            "MusicGenre" => Ok(Self::MusicGenre),
            "MusicVideo" => Ok(Self::MusicVideo),
            "Person" => Ok(Self::Person),
            "Photo" => Ok(Self::Photo),
            "PhotoAlbum" => Ok(Self::PhotoAlbum),
            "Playlist" => Ok(Self::Playlist),
            "PlaylistsFolder" => Ok(Self::PlaylistsFolder),
            "Program" => Ok(Self::Program),
            "Recording" => Ok(Self::Recording),
            "Season" => Ok(Self::Season),
            "Series" => Ok(Self::Series),
            "Studio" => Ok(Self::Studio),
            "Trailer" => Ok(Self::Trailer),
            "TvChannel" => Ok(Self::TvChannel),
            "TvProgram" => Ok(Self::TvProgram),
            "UserRootFolder" => Ok(Self::UserRootFolder),
            "UserView" => Ok(Self::UserView),
            "Video" => Ok(Self::Video),
            "Year" => Ok(Self::Year),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for BaseItemKind {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for BaseItemKind {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for BaseItemKind {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "This is used by the api to get information about a Person within a BaseItem."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"This is used by the api to get information about a Person within a BaseItem.\","]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"properties\": {"]
#[doc = "    \"Id\": {"]
#[doc = "      \"description\": \"Gets or sets the identifier.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"format\": \"uuid\""]
#[doc = "    },"]
#[doc = "    \"ImageBlurHashes\": {"]
#[doc = "      \"$ref\": \"#/$defs/ImageBlurHashes\""]
#[doc = "    },"]
#[doc = "    \"Name\": {"]
#[doc = "      \"description\": \"Gets or sets the name.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"PrimaryImageTag\": {"]
#[doc = "      \"description\": \"Gets or sets the primary image tag.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Role\": {"]
#[doc = "      \"description\": \"Gets or sets the role.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Type\": {"]
#[doc = "      \"description\": \"Gets or sets the type.\","]
#[doc = "      \"default\": \"Unknown\","]
#[doc = "      \"enum\": ["]
#[doc = "        \"Unknown\","]
#[doc = "        \"Actor\","]
#[doc = "        \"Director\","]
#[doc = "        \"Composer\","]
#[doc = "        \"Writer\","]
#[doc = "        \"GuestStar\","]
#[doc = "        \"Producer\","]
#[doc = "        \"Conductor\","]
#[doc = "        \"Lyricist\","]
#[doc = "        \"Arranger\","]
#[doc = "        \"Engineer\","]
#[doc = "        \"Mixer\","]
#[doc = "        \"Remixer\","]
#[doc = "        \"Creator\","]
#[doc = "        \"Artist\","]
#[doc = "        \"AlbumArtist\","]
#[doc = "        \"Author\","]
#[doc = "        \"Illustrator\","]
#[doc = "        \"Penciller\","]
#[doc = "        \"Inker\","]
#[doc = "        \"Colorist\","]
#[doc = "        \"Letterer\","]
#[doc = "        \"CoverArtist\","]
#[doc = "        \"Editor\","]
#[doc = "        \"Translator\","]
#[doc = "        \"Narrator\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/PersonKind\""]
#[doc = "        }"]
#[doc = "      ]"]
#[doc = "    }"]
#[doc = "  }"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug, Default)]
pub struct BaseItemPerson {
    #[doc = "Gets or sets the identifier."]
    #[serde(
        rename = "Id",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub id: ::std::option::Option<::uuid::Uuid>,
    #[serde(
        rename = "ImageBlurHashes",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub image_blur_hashes: ::std::option::Option<ImageBlurHashes>,
    #[doc = "Gets or sets the name."]
    #[serde(
        rename = "Name",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub name: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the primary image tag."]
    #[serde(
        rename = "PrimaryImageTag",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub primary_image_tag: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the role."]
    #[serde(
        rename = "Role",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub role: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "Type",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub type_: ::std::option::Option<PersonKind>,
}
#[doc = "Enum ChannelType."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"Enum ChannelType.\","]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"TV\","]
#[doc = "    \"Radio\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum ChannelType {
    #[serde(rename = "TV")]
    Tv,
    Radio,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for ChannelType {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::Tv => f.write_str("TV"),
            Self::Radio => f.write_str("Radio"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for ChannelType {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "TV" => Ok(Self::Tv),
            "Radio" => Ok(Self::Radio),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for ChannelType {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for ChannelType {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for ChannelType {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "Class ChapterInfo."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"Class ChapterInfo.\","]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"properties\": {"]
#[doc = "    \"ImageDateModified\": {"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"format\": \"date-time\""]
#[doc = "    },"]
#[doc = "    \"ImagePath\": {"]
#[doc = "      \"description\": \"Gets or sets the image path.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ImageTag\": {"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Name\": {"]
#[doc = "      \"description\": \"Gets or sets the name.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"StartPositionTicks\": {"]
#[doc = "      \"description\": \"Gets or sets the start position ticks.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int64\""]
#[doc = "    }"]
#[doc = "  }"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug, Default)]
pub struct ChapterInfo {
    #[serde(
        rename = "ImageDateModified",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub image_date_modified: ::std::option::Option<::chrono::DateTime<::chrono::offset::Utc>>,
    #[doc = "Gets or sets the image path."]
    #[serde(
        rename = "ImagePath",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub image_path: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "ImageTag",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub image_tag: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the name."]
    #[serde(
        rename = "Name",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub name: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the start position ticks."]
    #[serde(
        rename = "StartPositionTicks",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub start_position_ticks: ::std::option::Option<i64>,
}
#[doc = "Client capabilities dto."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"Client capabilities dto.\","]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"properties\": {"]
#[doc = "    \"AppStoreUrl\": {"]
#[doc = "      \"description\": \"Gets or sets the app store url.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"DeviceProfile\": {"]
#[doc = "      \"description\": \"Gets or sets the device profile.\","]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/DeviceProfile\""]
#[doc = "        }"]
#[doc = "      ],"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"IconUrl\": {"]
#[doc = "      \"description\": \"Gets or sets the icon url.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"PlayableMediaTypes\": {"]
#[doc = "      \"description\": \"Gets or sets the list of playable media types.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"$ref\": \"#/$defs/MediaType\""]
#[doc = "      }"]
#[doc = "    },"]
#[doc = "    \"SupportedCommands\": {"]
#[doc = "      \"description\": \"Gets or sets the list of supported commands.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"$ref\": \"#/$defs/GeneralCommandType\""]
#[doc = "      }"]
#[doc = "    },"]
#[doc = "    \"SupportsMediaControl\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether session supports media control.\","]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"SupportsPersistentIdentifier\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether session supports a persistent identifier.\","]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    }"]
#[doc = "  }"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug, Default)]
pub struct ClientCapabilitiesDto {
    #[doc = "Gets or sets the app store url."]
    #[serde(
        rename = "AppStoreUrl",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub app_store_url: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the device profile."]
    #[serde(
        rename = "DeviceProfile",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub device_profile: ::std::option::Option<DeviceProfile>,
    #[doc = "Gets or sets the icon url."]
    #[serde(
        rename = "IconUrl",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub icon_url: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the list of playable media types."]
    #[serde(
        rename = "PlayableMediaTypes",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub playable_media_types: ::std::vec::Vec<MediaType>,
    #[doc = "Gets or sets the list of supported commands."]
    #[serde(
        rename = "SupportedCommands",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub supported_commands: ::std::vec::Vec<GeneralCommandType>,
    #[doc = "Gets or sets a value indicating whether session supports media control."]
    #[serde(
        rename = "SupportsMediaControl",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub supports_media_control: ::std::option::Option<bool>,
    #[doc = "Gets or sets a value indicating whether session supports a persistent identifier."]
    #[serde(
        rename = "SupportsPersistentIdentifier",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub supports_persistent_identifier: ::std::option::Option<bool>,
}
#[doc = "Defines the MediaBrowser.Model.Dlna.CodecProfile."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"Defines the MediaBrowser.Model.Dlna.CodecProfile.\","]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"properties\": {"]
#[doc = "    \"ApplyConditions\": {"]
#[doc = "      \"description\": \"Gets or sets the list of MediaBrowser.Model.Dlna.ProfileCondition to apply if this profile is met.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"$ref\": \"#/$defs/ProfileCondition\""]
#[doc = "      }"]
#[doc = "    },"]
#[doc = "    \"Codec\": {"]
#[doc = "      \"description\": \"Gets or sets the codec(s) that this profile applies to.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Conditions\": {"]
#[doc = "      \"description\": \"Gets or sets the list of MediaBrowser.Model.Dlna.ProfileCondition which this profile must meet.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"$ref\": \"#/$defs/ProfileCondition\""]
#[doc = "      }"]
#[doc = "    },"]
#[doc = "    \"Container\": {"]
#[doc = "      \"description\": \"Gets or sets the container(s) which this profile will be applied to.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"SubContainer\": {"]
#[doc = "      \"description\": \"Gets or sets the sub-container(s) which this profile will be applied to.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Type\": {"]
#[doc = "      \"description\": \"Gets or sets the MediaBrowser.Model.Dlna.CodecType which this container must meet.\","]
#[doc = "      \"enum\": ["]
#[doc = "        \"Video\","]
#[doc = "        \"VideoAudio\","]
#[doc = "        \"Audio\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/CodecType\""]
#[doc = "        }"]
#[doc = "      ]"]
#[doc = "    }"]
#[doc = "  }"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug, Default)]
pub struct CodecProfile {
    #[doc = "Gets or sets the list of MediaBrowser.Model.Dlna.ProfileCondition to apply if this profile is met."]
    #[serde(
        rename = "ApplyConditions",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub apply_conditions: ::std::vec::Vec<ProfileCondition>,
    #[doc = "Gets or sets the codec(s) that this profile applies to."]
    #[serde(
        rename = "Codec",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub codec: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the list of MediaBrowser.Model.Dlna.ProfileCondition which this profile must meet."]
    #[serde(
        rename = "Conditions",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub conditions: ::std::vec::Vec<ProfileCondition>,
    #[doc = "Gets or sets the container(s) which this profile will be applied to."]
    #[serde(
        rename = "Container",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub container: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the sub-container(s) which this profile will be applied to."]
    #[serde(
        rename = "SubContainer",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub sub_container: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "Type",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub type_: ::std::option::Option<CodecType>,
}
#[doc = "The codec type of a codec profile."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"The codec type of a codec profile.\","]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"Video\","]
#[doc = "    \"VideoAudio\","]
#[doc = "    \"Audio\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum CodecType {
    Video,
    VideoAudio,
    Audio,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for CodecType {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::Video => f.write_str("Video"),
            Self::VideoAudio => f.write_str("VideoAudio"),
            Self::Audio => f.write_str("Audio"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for CodecType {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "Video" => Ok(Self::Video),
            "VideoAudio" => Ok(Self::VideoAudio),
            "Audio" => Ok(Self::Audio),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for CodecType {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for CodecType {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for CodecType {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "Collection type."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"Collection type.\","]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"unknown\","]
#[doc = "    \"movies\","]
#[doc = "    \"tvshows\","]
#[doc = "    \"music\","]
#[doc = "    \"musicvideos\","]
#[doc = "    \"trailers\","]
#[doc = "    \"homevideos\","]
#[doc = "    \"boxsets\","]
#[doc = "    \"books\","]
#[doc = "    \"photos\","]
#[doc = "    \"livetv\","]
#[doc = "    \"playlists\","]
#[doc = "    \"folders\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum CollectionType {
    #[serde(rename = "unknown")]
    Unknown,
    #[serde(rename = "movies")]
    Movies,
    #[serde(rename = "tvshows")]
    Tvshows,
    #[serde(rename = "music")]
    Music,
    #[serde(rename = "musicvideos")]
    Musicvideos,
    #[serde(rename = "trailers")]
    Trailers,
    #[serde(rename = "homevideos")]
    Homevideos,
    #[serde(rename = "boxsets")]
    Boxsets,
    #[serde(rename = "books")]
    Books,
    #[serde(rename = "photos")]
    Photos,
    #[serde(rename = "livetv")]
    Livetv,
    #[serde(rename = "playlists")]
    Playlists,
    #[serde(rename = "folders")]
    Folders,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for CollectionType {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::Unknown => f.write_str("unknown"),
            Self::Movies => f.write_str("movies"),
            Self::Tvshows => f.write_str("tvshows"),
            Self::Music => f.write_str("music"),
            Self::Musicvideos => f.write_str("musicvideos"),
            Self::Trailers => f.write_str("trailers"),
            Self::Homevideos => f.write_str("homevideos"),
            Self::Boxsets => f.write_str("boxsets"),
            Self::Books => f.write_str("books"),
            Self::Photos => f.write_str("photos"),
            Self::Livetv => f.write_str("livetv"),
            Self::Playlists => f.write_str("playlists"),
            Self::Folders => f.write_str("folders"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for CollectionType {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "unknown" => Ok(Self::Unknown),
            "movies" => Ok(Self::Movies),
            "tvshows" => Ok(Self::Tvshows),
            "music" => Ok(Self::Music),
            "musicvideos" => Ok(Self::Musicvideos),
            "trailers" => Ok(Self::Trailers),
            "homevideos" => Ok(Self::Homevideos),
            "boxsets" => Ok(Self::Boxsets),
            "books" => Ok(Self::Books),
            "photos" => Ok(Self::Photos),
            "livetv" => Ok(Self::Livetv),
            "playlists" => Ok(Self::Playlists),
            "folders" => Ok(Self::Folders),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for CollectionType {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for CollectionType {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for CollectionType {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "Defines the MediaBrowser.Model.Dlna.ContainerProfile."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"Defines the MediaBrowser.Model.Dlna.ContainerProfile.\","]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"properties\": {"]
#[doc = "    \"Conditions\": {"]
#[doc = "      \"description\": \"Gets or sets the list of MediaBrowser.Model.Dlna.ProfileCondition which this container will be applied to.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"$ref\": \"#/$defs/ProfileCondition\""]
#[doc = "      }"]
#[doc = "    },"]
#[doc = "    \"Container\": {"]
#[doc = "      \"description\": \"Gets or sets the container(s) which this container must meet.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"SubContainer\": {"]
#[doc = "      \"description\": \"Gets or sets the sub container(s) which this container must meet.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Type\": {"]
#[doc = "      \"description\": \"Gets or sets the MediaBrowser.Model.Dlna.DlnaProfileType which this container must meet.\","]
#[doc = "      \"enum\": ["]
#[doc = "        \"Audio\","]
#[doc = "        \"Video\","]
#[doc = "        \"Photo\","]
#[doc = "        \"Subtitle\","]
#[doc = "        \"Lyric\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/DlnaProfileType\""]
#[doc = "        }"]
#[doc = "      ]"]
#[doc = "    }"]
#[doc = "  }"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug, Default)]
pub struct ContainerProfile {
    #[doc = "Gets or sets the list of MediaBrowser.Model.Dlna.ProfileCondition which this container will be applied to."]
    #[serde(
        rename = "Conditions",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub conditions: ::std::vec::Vec<ProfileCondition>,
    #[doc = "Gets or sets the container(s) which this container must meet."]
    #[serde(
        rename = "Container",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub container: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the sub container(s) which this container must meet."]
    #[serde(
        rename = "SubContainer",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub sub_container: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "Type",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub type_: ::std::option::Option<DlnaProfileType>,
}
#[doc = "`DayOfWeek`"]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"Sunday\","]
#[doc = "    \"Monday\","]
#[doc = "    \"Tuesday\","]
#[doc = "    \"Wednesday\","]
#[doc = "    \"Thursday\","]
#[doc = "    \"Friday\","]
#[doc = "    \"Saturday\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum DayOfWeek {
    Sunday,
    Monday,
    Tuesday,
    Wednesday,
    Thursday,
    Friday,
    Saturday,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for DayOfWeek {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::Sunday => f.write_str("Sunday"),
            Self::Monday => f.write_str("Monday"),
            Self::Tuesday => f.write_str("Tuesday"),
            Self::Wednesday => f.write_str("Wednesday"),
            Self::Thursday => f.write_str("Thursday"),
            Self::Friday => f.write_str("Friday"),
            Self::Saturday => f.write_str("Saturday"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for DayOfWeek {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "Sunday" => Ok(Self::Sunday),
            "Monday" => Ok(Self::Monday),
            "Tuesday" => Ok(Self::Tuesday),
            "Wednesday" => Ok(Self::Wednesday),
            "Thursday" => Ok(Self::Thursday),
            "Friday" => Ok(Self::Friday),
            "Saturday" => Ok(Self::Saturday),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for DayOfWeek {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for DayOfWeek {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for DayOfWeek {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "A MediaBrowser.Model.Dlna.DeviceProfile represents a set of metadata which determines which content a certain device is able to play.\n\n\nSpecifically, it defines the supported <see cref=\"P:MediaBrowser.Model.Dlna.DeviceProfile.ContainerProfiles\">containers</see> and\n<see cref=\"P:MediaBrowser.Model.Dlna.DeviceProfile.CodecProfiles\">codecs</see> (video and/or audio, including codec profiles and levels)\nthe device is able to direct play (without transcoding or remuxing),\nas well as which <see cref=\"P:MediaBrowser.Model.Dlna.DeviceProfile.TranscodingProfiles\">containers/codecs to transcode to</see> in case it isn't."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"A MediaBrowser.Model.Dlna.DeviceProfile represents a set of metadata which determines which content a certain device is able to play.\\n\\n\\nSpecifically, it defines the supported <see cref=\\\"P:MediaBrowser.Model.Dlna.DeviceProfile.ContainerProfiles\\\">containers</see> and\\n<see cref=\\\"P:MediaBrowser.Model.Dlna.DeviceProfile.CodecProfiles\\\">codecs</see> (video and/or audio, including codec profiles and levels)\\nthe device is able to direct play (without transcoding or remuxing),\\nas well as which <see cref=\\\"P:MediaBrowser.Model.Dlna.DeviceProfile.TranscodingProfiles\\\">containers/codecs to transcode to</see> in case it isn't.\","]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"properties\": {"]
#[doc = "    \"CodecProfiles\": {"]
#[doc = "      \"description\": \"Gets or sets the codec profiles.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"$ref\": \"#/$defs/CodecProfile\""]
#[doc = "      }"]
#[doc = "    },"]
#[doc = "    \"ContainerProfiles\": {"]
#[doc = "      \"description\": \"Gets or sets the container profiles. Failing to meet these optional conditions causes transcoding to occur.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"$ref\": \"#/$defs/ContainerProfile\""]
#[doc = "      }"]
#[doc = "    },"]
#[doc = "    \"DirectPlayProfiles\": {"]
#[doc = "      \"description\": \"Gets or sets the direct play profiles.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"$ref\": \"#/$defs/DirectPlayProfile\""]
#[doc = "      }"]
#[doc = "    },"]
#[doc = "    \"Id\": {"]
#[doc = "      \"description\": \"Gets or sets the unique internal identifier.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"format\": \"uuid\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"MaxStaticBitrate\": {"]
#[doc = "      \"description\": \"Gets or sets the maximum allowed bitrate for statically streamed content (= direct played files).\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"MaxStaticMusicBitrate\": {"]
#[doc = "      \"description\": \"Gets or sets the maximum allowed bitrate for statically streamed (= direct played) music files.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"MaxStreamingBitrate\": {"]
#[doc = "      \"description\": \"Gets or sets the maximum allowed bitrate for all streamed content.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"MusicStreamingTranscodingBitrate\": {"]
#[doc = "      \"description\": \"Gets or sets the maximum allowed bitrate for transcoded music streams.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Name\": {"]
#[doc = "      \"description\": \"Gets or sets the name of this device profile. User profiles must have a unique name.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"SubtitleProfiles\": {"]
#[doc = "      \"description\": \"Gets or sets the subtitle profiles.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"$ref\": \"#/$defs/SubtitleProfile\""]
#[doc = "      }"]
#[doc = "    },"]
#[doc = "    \"TranscodingProfiles\": {"]
#[doc = "      \"description\": \"Gets or sets the transcoding profiles.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"$ref\": \"#/$defs/TranscodingProfile\""]
#[doc = "      }"]
#[doc = "    }"]
#[doc = "  }"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug, Default)]
pub struct DeviceProfile {
    #[doc = "Gets or sets the codec profiles."]
    #[serde(
        rename = "CodecProfiles",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub codec_profiles: ::std::vec::Vec<CodecProfile>,
    #[doc = "Gets or sets the container profiles. Failing to meet these optional conditions causes transcoding to occur."]
    #[serde(
        rename = "ContainerProfiles",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub container_profiles: ::std::vec::Vec<ContainerProfile>,
    #[doc = "Gets or sets the direct play profiles."]
    #[serde(
        rename = "DirectPlayProfiles",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub direct_play_profiles: ::std::vec::Vec<DirectPlayProfile>,
    #[doc = "Gets or sets the unique internal identifier."]
    #[serde(
        rename = "Id",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub id: ::std::option::Option<::uuid::Uuid>,
    #[doc = "Gets or sets the maximum allowed bitrate for statically streamed content (= direct played files)."]
    #[serde(
        rename = "MaxStaticBitrate",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub max_static_bitrate: ::std::option::Option<i32>,
    #[doc = "Gets or sets the maximum allowed bitrate for statically streamed (= direct played) music files."]
    #[serde(
        rename = "MaxStaticMusicBitrate",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub max_static_music_bitrate: ::std::option::Option<i32>,
    #[doc = "Gets or sets the maximum allowed bitrate for all streamed content."]
    #[serde(
        rename = "MaxStreamingBitrate",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub max_streaming_bitrate: ::std::option::Option<i32>,
    #[doc = "Gets or sets the maximum allowed bitrate for transcoded music streams."]
    #[serde(
        rename = "MusicStreamingTranscodingBitrate",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub music_streaming_transcoding_bitrate: ::std::option::Option<i32>,
    #[doc = "Gets or sets the name of this device profile. User profiles must have a unique name."]
    #[serde(
        rename = "Name",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub name: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the subtitle profiles."]
    #[serde(
        rename = "SubtitleProfiles",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub subtitle_profiles: ::std::vec::Vec<SubtitleProfile>,
    #[doc = "Gets or sets the transcoding profiles."]
    #[serde(
        rename = "TranscodingProfiles",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub transcoding_profiles: ::std::vec::Vec<TranscodingProfile>,
}
#[doc = "Defines the MediaBrowser.Model.Dlna.DirectPlayProfile."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"Defines the MediaBrowser.Model.Dlna.DirectPlayProfile.\","]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"properties\": {"]
#[doc = "    \"AudioCodec\": {"]
#[doc = "      \"description\": \"Gets or sets the audio codec.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Container\": {"]
#[doc = "      \"description\": \"Gets or sets the container.\","]
#[doc = "      \"type\": \"string\""]
#[doc = "    },"]
#[doc = "    \"Type\": {"]
#[doc = "      \"description\": \"Gets or sets the Dlna profile type.\","]
#[doc = "      \"enum\": ["]
#[doc = "        \"Audio\","]
#[doc = "        \"Video\","]
#[doc = "        \"Photo\","]
#[doc = "        \"Subtitle\","]
#[doc = "        \"Lyric\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/DlnaProfileType\""]
#[doc = "        }"]
#[doc = "      ]"]
#[doc = "    },"]
#[doc = "    \"VideoCodec\": {"]
#[doc = "      \"description\": \"Gets or sets the video codec.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    }"]
#[doc = "  }"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug, Default)]
pub struct DirectPlayProfile {
    #[doc = "Gets or sets the audio codec."]
    #[serde(
        rename = "AudioCodec",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub audio_codec: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the container."]
    #[serde(
        rename = "Container",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub container: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "Type",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub type_: ::std::option::Option<DlnaProfileType>,
    #[doc = "Gets or sets the video codec."]
    #[serde(
        rename = "VideoCodec",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub video_codec: ::std::option::Option<::std::string::String>,
}
#[doc = "`DlnaProfileType`"]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"Audio\","]
#[doc = "    \"Video\","]
#[doc = "    \"Photo\","]
#[doc = "    \"Subtitle\","]
#[doc = "    \"Lyric\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum DlnaProfileType {
    Audio,
    Video,
    Photo,
    Subtitle,
    Lyric,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for DlnaProfileType {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::Audio => f.write_str("Audio"),
            Self::Video => f.write_str("Video"),
            Self::Photo => f.write_str("Photo"),
            Self::Subtitle => f.write_str("Subtitle"),
            Self::Lyric => f.write_str("Lyric"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for DlnaProfileType {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "Audio" => Ok(Self::Audio),
            "Video" => Ok(Self::Video),
            "Photo" => Ok(Self::Photo),
            "Subtitle" => Ok(Self::Subtitle),
            "Lyric" => Ok(Self::Lyric),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for DlnaProfileType {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for DlnaProfileType {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for DlnaProfileType {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "An enum that represents a day of the week, weekdays, weekends, or all days."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"An enum that represents a day of the week, weekdays, weekends, or all days.\","]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"Sunday\","]
#[doc = "    \"Monday\","]
#[doc = "    \"Tuesday\","]
#[doc = "    \"Wednesday\","]
#[doc = "    \"Thursday\","]
#[doc = "    \"Friday\","]
#[doc = "    \"Saturday\","]
#[doc = "    \"Everyday\","]
#[doc = "    \"Weekday\","]
#[doc = "    \"Weekend\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum DynamicDayOfWeek {
    Sunday,
    Monday,
    Tuesday,
    Wednesday,
    Thursday,
    Friday,
    Saturday,
    Everyday,
    Weekday,
    Weekend,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for DynamicDayOfWeek {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::Sunday => f.write_str("Sunday"),
            Self::Monday => f.write_str("Monday"),
            Self::Tuesday => f.write_str("Tuesday"),
            Self::Wednesday => f.write_str("Wednesday"),
            Self::Thursday => f.write_str("Thursday"),
            Self::Friday => f.write_str("Friday"),
            Self::Saturday => f.write_str("Saturday"),
            Self::Everyday => f.write_str("Everyday"),
            Self::Weekday => f.write_str("Weekday"),
            Self::Weekend => f.write_str("Weekend"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for DynamicDayOfWeek {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "Sunday" => Ok(Self::Sunday),
            "Monday" => Ok(Self::Monday),
            "Tuesday" => Ok(Self::Tuesday),
            "Wednesday" => Ok(Self::Wednesday),
            "Thursday" => Ok(Self::Thursday),
            "Friday" => Ok(Self::Friday),
            "Saturday" => Ok(Self::Saturday),
            "Everyday" => Ok(Self::Everyday),
            "Weekday" => Ok(Self::Weekday),
            "Weekend" => Ok(Self::Weekend),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for DynamicDayOfWeek {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for DynamicDayOfWeek {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for DynamicDayOfWeek {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "The encoding context."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"The encoding context.\","]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"Streaming\","]
#[doc = "    \"Static\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum EncodingContext {
    Streaming,
    Static,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for EncodingContext {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::Streaming => f.write_str("Streaming"),
            Self::Static => f.write_str("Static"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for EncodingContext {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "Streaming" => Ok(Self::Streaming),
            "Static" => Ok(Self::Static),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for EncodingContext {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for EncodingContext {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for EncodingContext {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "`ExternalUrl`"]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"properties\": {"]
#[doc = "    \"Name\": {"]
#[doc = "      \"description\": \"Gets or sets the name.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Url\": {"]
#[doc = "      \"description\": \"Gets or sets the type of the item.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    }"]
#[doc = "  }"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug, Default)]
pub struct ExternalUrl {
    #[doc = "Gets or sets the name."]
    #[serde(
        rename = "Name",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub name: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the type of the item."]
    #[serde(
        rename = "Url",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub url: ::std::option::Option<::std::string::String>,
}
#[doc = "`ExtraType`"]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"Unknown\","]
#[doc = "    \"Clip\","]
#[doc = "    \"Trailer\","]
#[doc = "    \"BehindTheScenes\","]
#[doc = "    \"DeletedScene\","]
#[doc = "    \"Interview\","]
#[doc = "    \"Scene\","]
#[doc = "    \"Sample\","]
#[doc = "    \"ThemeSong\","]
#[doc = "    \"ThemeVideo\","]
#[doc = "    \"Featurette\","]
#[doc = "    \"Short\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum ExtraType {
    Unknown,
    Clip,
    Trailer,
    BehindTheScenes,
    DeletedScene,
    Interview,
    Scene,
    Sample,
    ThemeSong,
    ThemeVideo,
    Featurette,
    Short,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for ExtraType {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::Unknown => f.write_str("Unknown"),
            Self::Clip => f.write_str("Clip"),
            Self::Trailer => f.write_str("Trailer"),
            Self::BehindTheScenes => f.write_str("BehindTheScenes"),
            Self::DeletedScene => f.write_str("DeletedScene"),
            Self::Interview => f.write_str("Interview"),
            Self::Scene => f.write_str("Scene"),
            Self::Sample => f.write_str("Sample"),
            Self::ThemeSong => f.write_str("ThemeSong"),
            Self::ThemeVideo => f.write_str("ThemeVideo"),
            Self::Featurette => f.write_str("Featurette"),
            Self::Short => f.write_str("Short"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for ExtraType {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "Unknown" => Ok(Self::Unknown),
            "Clip" => Ok(Self::Clip),
            "Trailer" => Ok(Self::Trailer),
            "BehindTheScenes" => Ok(Self::BehindTheScenes),
            "DeletedScene" => Ok(Self::DeletedScene),
            "Interview" => Ok(Self::Interview),
            "Scene" => Ok(Self::Scene),
            "Sample" => Ok(Self::Sample),
            "ThemeSong" => Ok(Self::ThemeSong),
            "ThemeVideo" => Ok(Self::ThemeVideo),
            "Featurette" => Ok(Self::Featurette),
            "Short" => Ok(Self::Short),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for ExtraType {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for ExtraType {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for ExtraType {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "This exists simply to identify a set of known commands."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"This exists simply to identify a set of known commands.\","]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"MoveUp\","]
#[doc = "    \"MoveDown\","]
#[doc = "    \"MoveLeft\","]
#[doc = "    \"MoveRight\","]
#[doc = "    \"PageUp\","]
#[doc = "    \"PageDown\","]
#[doc = "    \"PreviousLetter\","]
#[doc = "    \"NextLetter\","]
#[doc = "    \"ToggleOsd\","]
#[doc = "    \"ToggleContextMenu\","]
#[doc = "    \"Select\","]
#[doc = "    \"Back\","]
#[doc = "    \"TakeScreenshot\","]
#[doc = "    \"SendKey\","]
#[doc = "    \"SendString\","]
#[doc = "    \"GoHome\","]
#[doc = "    \"GoToSettings\","]
#[doc = "    \"VolumeUp\","]
#[doc = "    \"VolumeDown\","]
#[doc = "    \"Mute\","]
#[doc = "    \"Unmute\","]
#[doc = "    \"ToggleMute\","]
#[doc = "    \"SetVolume\","]
#[doc = "    \"SetAudioStreamIndex\","]
#[doc = "    \"SetSubtitleStreamIndex\","]
#[doc = "    \"ToggleFullscreen\","]
#[doc = "    \"DisplayContent\","]
#[doc = "    \"GoToSearch\","]
#[doc = "    \"DisplayMessage\","]
#[doc = "    \"SetRepeatMode\","]
#[doc = "    \"ChannelUp\","]
#[doc = "    \"ChannelDown\","]
#[doc = "    \"Guide\","]
#[doc = "    \"ToggleStats\","]
#[doc = "    \"PlayMediaSource\","]
#[doc = "    \"PlayTrailers\","]
#[doc = "    \"SetShuffleQueue\","]
#[doc = "    \"PlayState\","]
#[doc = "    \"PlayNext\","]
#[doc = "    \"ToggleOsdMenu\","]
#[doc = "    \"Play\","]
#[doc = "    \"SetMaxStreamingBitrate\","]
#[doc = "    \"SetPlaybackOrder\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum GeneralCommandType {
    MoveUp,
    MoveDown,
    MoveLeft,
    MoveRight,
    PageUp,
    PageDown,
    PreviousLetter,
    NextLetter,
    ToggleOsd,
    ToggleContextMenu,
    Select,
    Back,
    TakeScreenshot,
    SendKey,
    SendString,
    GoHome,
    GoToSettings,
    VolumeUp,
    VolumeDown,
    Mute,
    Unmute,
    ToggleMute,
    SetVolume,
    SetAudioStreamIndex,
    SetSubtitleStreamIndex,
    ToggleFullscreen,
    DisplayContent,
    GoToSearch,
    DisplayMessage,
    SetRepeatMode,
    ChannelUp,
    ChannelDown,
    Guide,
    ToggleStats,
    PlayMediaSource,
    PlayTrailers,
    SetShuffleQueue,
    PlayState,
    PlayNext,
    ToggleOsdMenu,
    Play,
    SetMaxStreamingBitrate,
    SetPlaybackOrder,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for GeneralCommandType {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::MoveUp => f.write_str("MoveUp"),
            Self::MoveDown => f.write_str("MoveDown"),
            Self::MoveLeft => f.write_str("MoveLeft"),
            Self::MoveRight => f.write_str("MoveRight"),
            Self::PageUp => f.write_str("PageUp"),
            Self::PageDown => f.write_str("PageDown"),
            Self::PreviousLetter => f.write_str("PreviousLetter"),
            Self::NextLetter => f.write_str("NextLetter"),
            Self::ToggleOsd => f.write_str("ToggleOsd"),
            Self::ToggleContextMenu => f.write_str("ToggleContextMenu"),
            Self::Select => f.write_str("Select"),
            Self::Back => f.write_str("Back"),
            Self::TakeScreenshot => f.write_str("TakeScreenshot"),
            Self::SendKey => f.write_str("SendKey"),
            Self::SendString => f.write_str("SendString"),
            Self::GoHome => f.write_str("GoHome"),
            Self::GoToSettings => f.write_str("GoToSettings"),
            Self::VolumeUp => f.write_str("VolumeUp"),
            Self::VolumeDown => f.write_str("VolumeDown"),
            Self::Mute => f.write_str("Mute"),
            Self::Unmute => f.write_str("Unmute"),
            Self::ToggleMute => f.write_str("ToggleMute"),
            Self::SetVolume => f.write_str("SetVolume"),
            Self::SetAudioStreamIndex => f.write_str("SetAudioStreamIndex"),
            Self::SetSubtitleStreamIndex => f.write_str("SetSubtitleStreamIndex"),
            Self::ToggleFullscreen => f.write_str("ToggleFullscreen"),
            Self::DisplayContent => f.write_str("DisplayContent"),
            Self::GoToSearch => f.write_str("GoToSearch"),
            Self::DisplayMessage => f.write_str("DisplayMessage"),
            Self::SetRepeatMode => f.write_str("SetRepeatMode"),
            Self::ChannelUp => f.write_str("ChannelUp"),
            Self::ChannelDown => f.write_str("ChannelDown"),
            Self::Guide => f.write_str("Guide"),
            Self::ToggleStats => f.write_str("ToggleStats"),
            Self::PlayMediaSource => f.write_str("PlayMediaSource"),
            Self::PlayTrailers => f.write_str("PlayTrailers"),
            Self::SetShuffleQueue => f.write_str("SetShuffleQueue"),
            Self::PlayState => f.write_str("PlayState"),
            Self::PlayNext => f.write_str("PlayNext"),
            Self::ToggleOsdMenu => f.write_str("ToggleOsdMenu"),
            Self::Play => f.write_str("Play"),
            Self::SetMaxStreamingBitrate => f.write_str("SetMaxStreamingBitrate"),
            Self::SetPlaybackOrder => f.write_str("SetPlaybackOrder"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for GeneralCommandType {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "MoveUp" => Ok(Self::MoveUp),
            "MoveDown" => Ok(Self::MoveDown),
            "MoveLeft" => Ok(Self::MoveLeft),
            "MoveRight" => Ok(Self::MoveRight),
            "PageUp" => Ok(Self::PageUp),
            "PageDown" => Ok(Self::PageDown),
            "PreviousLetter" => Ok(Self::PreviousLetter),
            "NextLetter" => Ok(Self::NextLetter),
            "ToggleOsd" => Ok(Self::ToggleOsd),
            "ToggleContextMenu" => Ok(Self::ToggleContextMenu),
            "Select" => Ok(Self::Select),
            "Back" => Ok(Self::Back),
            "TakeScreenshot" => Ok(Self::TakeScreenshot),
            "SendKey" => Ok(Self::SendKey),
            "SendString" => Ok(Self::SendString),
            "GoHome" => Ok(Self::GoHome),
            "GoToSettings" => Ok(Self::GoToSettings),
            "VolumeUp" => Ok(Self::VolumeUp),
            "VolumeDown" => Ok(Self::VolumeDown),
            "Mute" => Ok(Self::Mute),
            "Unmute" => Ok(Self::Unmute),
            "ToggleMute" => Ok(Self::ToggleMute),
            "SetVolume" => Ok(Self::SetVolume),
            "SetAudioStreamIndex" => Ok(Self::SetAudioStreamIndex),
            "SetSubtitleStreamIndex" => Ok(Self::SetSubtitleStreamIndex),
            "ToggleFullscreen" => Ok(Self::ToggleFullscreen),
            "DisplayContent" => Ok(Self::DisplayContent),
            "GoToSearch" => Ok(Self::GoToSearch),
            "DisplayMessage" => Ok(Self::DisplayMessage),
            "SetRepeatMode" => Ok(Self::SetRepeatMode),
            "ChannelUp" => Ok(Self::ChannelUp),
            "ChannelDown" => Ok(Self::ChannelDown),
            "Guide" => Ok(Self::Guide),
            "ToggleStats" => Ok(Self::ToggleStats),
            "PlayMediaSource" => Ok(Self::PlayMediaSource),
            "PlayTrailers" => Ok(Self::PlayTrailers),
            "SetShuffleQueue" => Ok(Self::SetShuffleQueue),
            "PlayState" => Ok(Self::PlayState),
            "PlayNext" => Ok(Self::PlayNext),
            "ToggleOsdMenu" => Ok(Self::ToggleOsdMenu),
            "Play" => Ok(Self::Play),
            "SetMaxStreamingBitrate" => Ok(Self::SetMaxStreamingBitrate),
            "SetPlaybackOrder" => Ok(Self::SetPlaybackOrder),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for GeneralCommandType {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for GeneralCommandType {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for GeneralCommandType {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "Enum containing hardware acceleration types."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"Enum containing hardware acceleration types.\","]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"none\","]
#[doc = "    \"amf\","]
#[doc = "    \"qsv\","]
#[doc = "    \"nvenc\","]
#[doc = "    \"v4l2m2m\","]
#[doc = "    \"vaapi\","]
#[doc = "    \"videotoolbox\","]
#[doc = "    \"rkmpp\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum HardwareAccelerationType {
    #[serde(rename = "none")]
    None,
    #[serde(rename = "amf")]
    Amf,
    #[serde(rename = "qsv")]
    Qsv,
    #[serde(rename = "nvenc")]
    Nvenc,
    #[serde(rename = "v4l2m2m")]
    V4l2m2m,
    #[serde(rename = "vaapi")]
    Vaapi,
    #[serde(rename = "videotoolbox")]
    Videotoolbox,
    #[serde(rename = "rkmpp")]
    Rkmpp,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for HardwareAccelerationType {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::None => f.write_str("none"),
            Self::Amf => f.write_str("amf"),
            Self::Qsv => f.write_str("qsv"),
            Self::Nvenc => f.write_str("nvenc"),
            Self::V4l2m2m => f.write_str("v4l2m2m"),
            Self::Vaapi => f.write_str("vaapi"),
            Self::Videotoolbox => f.write_str("videotoolbox"),
            Self::Rkmpp => f.write_str("rkmpp"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for HardwareAccelerationType {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "none" => Ok(Self::None),
            "amf" => Ok(Self::Amf),
            "qsv" => Ok(Self::Qsv),
            "nvenc" => Ok(Self::Nvenc),
            "v4l2m2m" => Ok(Self::V4l2m2m),
            "vaapi" => Ok(Self::Vaapi),
            "videotoolbox" => Ok(Self::Videotoolbox),
            "rkmpp" => Ok(Self::Rkmpp),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for HardwareAccelerationType {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for HardwareAccelerationType {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for HardwareAccelerationType {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "`ImageBlurHashes`"]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"properties\": {"]
#[doc = "    \"Art\": {"]
#[doc = "      \"type\": \"object\","]
#[doc = "      \"additionalProperties\": {"]
#[doc = "        \"type\": \"string\","]
#[doc = "        \"nullable\": true"]
#[doc = "      }"]
#[doc = "    },"]
#[doc = "    \"Backdrop\": {"]
#[doc = "      \"type\": \"object\","]
#[doc = "      \"additionalProperties\": {"]
#[doc = "        \"type\": \"string\","]
#[doc = "        \"nullable\": true"]
#[doc = "      }"]
#[doc = "    },"]
#[doc = "    \"Banner\": {"]
#[doc = "      \"type\": \"object\","]
#[doc = "      \"additionalProperties\": {"]
#[doc = "        \"type\": \"string\","]
#[doc = "        \"nullable\": true"]
#[doc = "      }"]
#[doc = "    },"]
#[doc = "    \"Box\": {"]
#[doc = "      \"type\": \"object\","]
#[doc = "      \"additionalProperties\": {"]
#[doc = "        \"type\": \"string\","]
#[doc = "        \"nullable\": true"]
#[doc = "      }"]
#[doc = "    },"]
#[doc = "    \"BoxRear\": {"]
#[doc = "      \"type\": \"object\","]
#[doc = "      \"additionalProperties\": {"]
#[doc = "        \"type\": \"string\","]
#[doc = "        \"nullable\": true"]
#[doc = "      }"]
#[doc = "    },"]
#[doc = "    \"Chapter\": {"]
#[doc = "      \"type\": \"object\","]
#[doc = "      \"additionalProperties\": {"]
#[doc = "        \"type\": \"string\","]
#[doc = "        \"nullable\": true"]
#[doc = "      }"]
#[doc = "    },"]
#[doc = "    \"Disc\": {"]
#[doc = "      \"type\": \"object\","]
#[doc = "      \"additionalProperties\": {"]
#[doc = "        \"type\": \"string\","]
#[doc = "        \"nullable\": true"]
#[doc = "      }"]
#[doc = "    },"]
#[doc = "    \"Logo\": {"]
#[doc = "      \"type\": \"object\","]
#[doc = "      \"additionalProperties\": {"]
#[doc = "        \"type\": \"string\","]
#[doc = "        \"nullable\": true"]
#[doc = "      }"]
#[doc = "    },"]
#[doc = "    \"Menu\": {"]
#[doc = "      \"type\": \"object\","]
#[doc = "      \"additionalProperties\": {"]
#[doc = "        \"type\": \"string\","]
#[doc = "        \"nullable\": true"]
#[doc = "      }"]
#[doc = "    },"]
#[doc = "    \"Primary\": {"]
#[doc = "      \"type\": \"object\","]
#[doc = "      \"additionalProperties\": {"]
#[doc = "        \"type\": \"string\","]
#[doc = "        \"nullable\": true"]
#[doc = "      }"]
#[doc = "    },"]
#[doc = "    \"Profile\": {"]
#[doc = "      \"type\": \"object\","]
#[doc = "      \"additionalProperties\": {"]
#[doc = "        \"type\": \"string\","]
#[doc = "        \"nullable\": true"]
#[doc = "      }"]
#[doc = "    },"]
#[doc = "    \"Screenshot\": {"]
#[doc = "      \"type\": \"object\","]
#[doc = "      \"additionalProperties\": {"]
#[doc = "        \"type\": \"string\","]
#[doc = "        \"nullable\": true"]
#[doc = "      }"]
#[doc = "    },"]
#[doc = "    \"Thumb\": {"]
#[doc = "      \"type\": \"object\","]
#[doc = "      \"additionalProperties\": {"]
#[doc = "        \"type\": \"string\","]
#[doc = "        \"nullable\": true"]
#[doc = "      }"]
#[doc = "    }"]
#[doc = "  },"]
#[doc = "  \"nullable\": true"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug, Default)]
pub struct ImageBlurHashes {
    #[serde(
        rename = "Art",
        default,
        skip_serializing_if = ":: std :: collections :: HashMap::is_empty"
    )]
    pub art: ::std::collections::HashMap<::std::string::String, ::std::string::String>,
    #[serde(
        rename = "Backdrop",
        default,
        skip_serializing_if = ":: std :: collections :: HashMap::is_empty"
    )]
    pub backdrop: ::std::collections::HashMap<::std::string::String, ::std::string::String>,
    #[serde(
        rename = "Banner",
        default,
        skip_serializing_if = ":: std :: collections :: HashMap::is_empty"
    )]
    pub banner: ::std::collections::HashMap<::std::string::String, ::std::string::String>,
    #[serde(
        rename = "Box",
        default,
        skip_serializing_if = ":: std :: collections :: HashMap::is_empty"
    )]
    pub box_: ::std::collections::HashMap<::std::string::String, ::std::string::String>,
    #[serde(
        rename = "BoxRear",
        default,
        skip_serializing_if = ":: std :: collections :: HashMap::is_empty"
    )]
    pub box_rear: ::std::collections::HashMap<::std::string::String, ::std::string::String>,
    #[serde(
        rename = "Chapter",
        default,
        skip_serializing_if = ":: std :: collections :: HashMap::is_empty"
    )]
    pub chapter: ::std::collections::HashMap<::std::string::String, ::std::string::String>,
    #[serde(
        rename = "Disc",
        default,
        skip_serializing_if = ":: std :: collections :: HashMap::is_empty"
    )]
    pub disc: ::std::collections::HashMap<::std::string::String, ::std::string::String>,
    #[serde(
        rename = "Logo",
        default,
        skip_serializing_if = ":: std :: collections :: HashMap::is_empty"
    )]
    pub logo: ::std::collections::HashMap<::std::string::String, ::std::string::String>,
    #[serde(
        rename = "Menu",
        default,
        skip_serializing_if = ":: std :: collections :: HashMap::is_empty"
    )]
    pub menu: ::std::collections::HashMap<::std::string::String, ::std::string::String>,
    #[serde(
        rename = "Primary",
        default,
        skip_serializing_if = ":: std :: collections :: HashMap::is_empty"
    )]
    pub primary: ::std::collections::HashMap<::std::string::String, ::std::string::String>,
    #[serde(
        rename = "Profile",
        default,
        skip_serializing_if = ":: std :: collections :: HashMap::is_empty"
    )]
    pub profile: ::std::collections::HashMap<::std::string::String, ::std::string::String>,
    #[serde(
        rename = "Screenshot",
        default,
        skip_serializing_if = ":: std :: collections :: HashMap::is_empty"
    )]
    pub screenshot: ::std::collections::HashMap<::std::string::String, ::std::string::String>,
    #[serde(
        rename = "Thumb",
        default,
        skip_serializing_if = ":: std :: collections :: HashMap::is_empty"
    )]
    pub thumb: ::std::collections::HashMap<::std::string::String, ::std::string::String>,
}
#[doc = "`ImageOrientation`"]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"TopLeft\","]
#[doc = "    \"TopRight\","]
#[doc = "    \"BottomRight\","]
#[doc = "    \"BottomLeft\","]
#[doc = "    \"LeftTop\","]
#[doc = "    \"RightTop\","]
#[doc = "    \"RightBottom\","]
#[doc = "    \"LeftBottom\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum ImageOrientation {
    TopLeft,
    TopRight,
    BottomRight,
    BottomLeft,
    LeftTop,
    RightTop,
    RightBottom,
    LeftBottom,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for ImageOrientation {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::TopLeft => f.write_str("TopLeft"),
            Self::TopRight => f.write_str("TopRight"),
            Self::BottomRight => f.write_str("BottomRight"),
            Self::BottomLeft => f.write_str("BottomLeft"),
            Self::LeftTop => f.write_str("LeftTop"),
            Self::RightTop => f.write_str("RightTop"),
            Self::RightBottom => f.write_str("RightBottom"),
            Self::LeftBottom => f.write_str("LeftBottom"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for ImageOrientation {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "TopLeft" => Ok(Self::TopLeft),
            "TopRight" => Ok(Self::TopRight),
            "BottomRight" => Ok(Self::BottomRight),
            "BottomLeft" => Ok(Self::BottomLeft),
            "LeftTop" => Ok(Self::LeftTop),
            "RightTop" => Ok(Self::RightTop),
            "RightBottom" => Ok(Self::RightBottom),
            "LeftBottom" => Ok(Self::LeftBottom),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for ImageOrientation {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for ImageOrientation {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for ImageOrientation {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "Enum IsoType."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"Enum IsoType.\","]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"Dvd\","]
#[doc = "    \"BluRay\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum IsoType {
    Dvd,
    BluRay,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for IsoType {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::Dvd => f.write_str("Dvd"),
            Self::BluRay => f.write_str("BluRay"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for IsoType {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "Dvd" => Ok(Self::Dvd),
            "BluRay" => Ok(Self::BluRay),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for IsoType {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for IsoType {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for IsoType {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "Class LibraryUpdateInfo."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"Class LibraryUpdateInfo.\","]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"properties\": {"]
#[doc = "    \"CollectionFolders\": {"]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"type\": \"string\""]
#[doc = "      }"]
#[doc = "    },"]
#[doc = "    \"FoldersAddedTo\": {"]
#[doc = "      \"description\": \"Gets or sets the folders added to.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"type\": \"string\""]
#[doc = "      }"]
#[doc = "    },"]
#[doc = "    \"FoldersRemovedFrom\": {"]
#[doc = "      \"description\": \"Gets or sets the folders removed from.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"type\": \"string\""]
#[doc = "      }"]
#[doc = "    },"]
#[doc = "    \"IsEmpty\": {"]
#[doc = "      \"readOnly\": true,"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"ItemsAdded\": {"]
#[doc = "      \"description\": \"Gets or sets the items added.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"type\": \"string\""]
#[doc = "      }"]
#[doc = "    },"]
#[doc = "    \"ItemsRemoved\": {"]
#[doc = "      \"description\": \"Gets or sets the items removed.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"type\": \"string\""]
#[doc = "      }"]
#[doc = "    },"]
#[doc = "    \"ItemsUpdated\": {"]
#[doc = "      \"description\": \"Gets or sets the items updated.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"type\": \"string\""]
#[doc = "      }"]
#[doc = "    }"]
#[doc = "  }"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug, Default)]
pub struct LibraryUpdateInfo {
    #[serde(
        rename = "CollectionFolders",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub collection_folders: ::std::vec::Vec<::std::string::String>,
    #[doc = "Gets or sets the folders added to."]
    #[serde(
        rename = "FoldersAddedTo",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub folders_added_to: ::std::vec::Vec<::std::string::String>,
    #[doc = "Gets or sets the folders removed from."]
    #[serde(
        rename = "FoldersRemovedFrom",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub folders_removed_from: ::std::vec::Vec<::std::string::String>,
    #[serde(
        rename = "IsEmpty",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub is_empty: ::std::option::Option<bool>,
    #[doc = "Gets or sets the items added."]
    #[serde(
        rename = "ItemsAdded",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub items_added: ::std::vec::Vec<::std::string::String>,
    #[doc = "Gets or sets the items removed."]
    #[serde(
        rename = "ItemsRemoved",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub items_removed: ::std::vec::Vec<::std::string::String>,
    #[doc = "Gets or sets the items updated."]
    #[serde(
        rename = "ItemsUpdated",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub items_updated: ::std::vec::Vec<::std::string::String>,
}
#[doc = "Enum LocationType."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"Enum LocationType.\","]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"FileSystem\","]
#[doc = "    \"Remote\","]
#[doc = "    \"Virtual\","]
#[doc = "    \"Offline\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum LocationType {
    FileSystem,
    Remote,
    Virtual,
    Offline,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for LocationType {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::FileSystem => f.write_str("FileSystem"),
            Self::Remote => f.write_str("Remote"),
            Self::Virtual => f.write_str("Virtual"),
            Self::Offline => f.write_str("Offline"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for LocationType {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "FileSystem" => Ok(Self::FileSystem),
            "Remote" => Ok(Self::Remote),
            "Virtual" => Ok(Self::Virtual),
            "Offline" => Ok(Self::Offline),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for LocationType {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for LocationType {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for LocationType {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "Class MediaAttachment."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"Class MediaAttachment.\","]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"properties\": {"]
#[doc = "    \"Codec\": {"]
#[doc = "      \"description\": \"Gets or sets the codec.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"CodecTag\": {"]
#[doc = "      \"description\": \"Gets or sets the codec tag.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Comment\": {"]
#[doc = "      \"description\": \"Gets or sets the comment.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"DeliveryUrl\": {"]
#[doc = "      \"description\": \"Gets or sets the delivery URL.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"FileName\": {"]
#[doc = "      \"description\": \"Gets or sets the filename.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Index\": {"]
#[doc = "      \"description\": \"Gets or sets the index.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\""]
#[doc = "    },"]
#[doc = "    \"MimeType\": {"]
#[doc = "      \"description\": \"Gets or sets the MIME type.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    }"]
#[doc = "  }"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug, Default)]
pub struct MediaAttachment {
    #[doc = "Gets or sets the codec."]
    #[serde(
        rename = "Codec",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub codec: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the codec tag."]
    #[serde(
        rename = "CodecTag",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub codec_tag: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the comment."]
    #[serde(
        rename = "Comment",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub comment: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the delivery URL."]
    #[serde(
        rename = "DeliveryUrl",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub delivery_url: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the filename."]
    #[serde(
        rename = "FileName",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub file_name: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the index."]
    #[serde(
        rename = "Index",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub index: ::std::option::Option<i32>,
    #[doc = "Gets or sets the MIME type."]
    #[serde(
        rename = "MimeType",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub mime_type: ::std::option::Option<::std::string::String>,
}
#[doc = "`MediaProtocol`"]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"File\","]
#[doc = "    \"Http\","]
#[doc = "    \"Rtmp\","]
#[doc = "    \"Rtsp\","]
#[doc = "    \"Udp\","]
#[doc = "    \"Rtp\","]
#[doc = "    \"Ftp\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum MediaProtocol {
    File,
    Http,
    Rtmp,
    Rtsp,
    Udp,
    Rtp,
    Ftp,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for MediaProtocol {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::File => f.write_str("File"),
            Self::Http => f.write_str("Http"),
            Self::Rtmp => f.write_str("Rtmp"),
            Self::Rtsp => f.write_str("Rtsp"),
            Self::Udp => f.write_str("Udp"),
            Self::Rtp => f.write_str("Rtp"),
            Self::Ftp => f.write_str("Ftp"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for MediaProtocol {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "File" => Ok(Self::File),
            "Http" => Ok(Self::Http),
            "Rtmp" => Ok(Self::Rtmp),
            "Rtsp" => Ok(Self::Rtsp),
            "Udp" => Ok(Self::Udp),
            "Rtp" => Ok(Self::Rtp),
            "Ftp" => Ok(Self::Ftp),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for MediaProtocol {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for MediaProtocol {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for MediaProtocol {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "Api model for MediaSegment's."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"Api model for MediaSegment's.\","]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"properties\": {"]
#[doc = "    \"EndTicks\": {"]
#[doc = "      \"description\": \"Gets or sets the end of the segment.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int64\""]
#[doc = "    },"]
#[doc = "    \"Id\": {"]
#[doc = "      \"description\": \"Gets or sets the id of the media segment.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"format\": \"uuid\""]
#[doc = "    },"]
#[doc = "    \"ItemId\": {"]
#[doc = "      \"description\": \"Gets or sets the id of the associated item.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"format\": \"uuid\""]
#[doc = "    },"]
#[doc = "    \"StartTicks\": {"]
#[doc = "      \"description\": \"Gets or sets the start of the segment.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int64\""]
#[doc = "    },"]
#[doc = "    \"Type\": {"]
#[doc = "      \"description\": \"Gets or sets the type of content this segment defines.\","]
#[doc = "      \"default\": \"Unknown\","]
#[doc = "      \"enum\": ["]
#[doc = "        \"Unknown\","]
#[doc = "        \"Commercial\","]
#[doc = "        \"Preview\","]
#[doc = "        \"Recap\","]
#[doc = "        \"Outro\","]
#[doc = "        \"Intro\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/MediaSegmentType\""]
#[doc = "        }"]
#[doc = "      ]"]
#[doc = "    }"]
#[doc = "  }"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug, Default)]
pub struct MediaSegmentDto {
    #[doc = "Gets or sets the end of the segment."]
    #[serde(
        rename = "EndTicks",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub end_ticks: ::std::option::Option<i64>,
    #[doc = "Gets or sets the id of the media segment."]
    #[serde(
        rename = "Id",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub id: ::std::option::Option<::uuid::Uuid>,
    #[doc = "Gets or sets the id of the associated item."]
    #[serde(
        rename = "ItemId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub item_id: ::std::option::Option<::uuid::Uuid>,
    #[doc = "Gets or sets the start of the segment."]
    #[serde(
        rename = "StartTicks",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub start_ticks: ::std::option::Option<i64>,
    #[serde(
        rename = "Type",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub type_: ::std::option::Option<MediaSegmentType>,
}
#[doc = "Query result container."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"Query result container.\","]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"properties\": {"]
#[doc = "    \"Items\": {"]
#[doc = "      \"description\": \"Gets or sets the items.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"$ref\": \"#/$defs/MediaSegmentDto\""]
#[doc = "      }"]
#[doc = "    },"]
#[doc = "    \"StartIndex\": {"]
#[doc = "      \"description\": \"Gets or sets the index of the first record in Items.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\""]
#[doc = "    },"]
#[doc = "    \"TotalRecordCount\": {"]
#[doc = "      \"description\": \"Gets or sets the total number of records available.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\""]
#[doc = "    }"]
#[doc = "  }"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug, Default)]
pub struct MediaSegmentDtoQueryResult {
    #[doc = "Gets or sets the items."]
    #[serde(
        rename = "Items",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub items: ::std::vec::Vec<MediaSegmentDto>,
    #[doc = "Gets or sets the index of the first record in Items."]
    #[serde(
        rename = "StartIndex",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub start_index: ::std::option::Option<i32>,
    #[doc = "Gets or sets the total number of records available."]
    #[serde(
        rename = "TotalRecordCount",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub total_record_count: ::std::option::Option<i32>,
}
#[doc = "Defines the types of content an individual Jellyfin.Database.Implementations.Entities.MediaSegment represents."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"Defines the types of content an individual Jellyfin.Database.Implementations.Entities.MediaSegment represents.\","]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"Unknown\","]
#[doc = "    \"Commercial\","]
#[doc = "    \"Preview\","]
#[doc = "    \"Recap\","]
#[doc = "    \"Outro\","]
#[doc = "    \"Intro\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum MediaSegmentType {
    Unknown,
    Commercial,
    Preview,
    Recap,
    Outro,
    Intro,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for MediaSegmentType {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::Unknown => f.write_str("Unknown"),
            Self::Commercial => f.write_str("Commercial"),
            Self::Preview => f.write_str("Preview"),
            Self::Recap => f.write_str("Recap"),
            Self::Outro => f.write_str("Outro"),
            Self::Intro => f.write_str("Intro"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for MediaSegmentType {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "Unknown" => Ok(Self::Unknown),
            "Commercial" => Ok(Self::Commercial),
            "Preview" => Ok(Self::Preview),
            "Recap" => Ok(Self::Recap),
            "Outro" => Ok(Self::Outro),
            "Intro" => Ok(Self::Intro),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for MediaSegmentType {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for MediaSegmentType {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for MediaSegmentType {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "`MediaSourceInfo`"]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"properties\": {"]
#[doc = "    \"AnalyzeDurationMs\": {"]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Bitrate\": {"]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"BufferMs\": {"]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Container\": {"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"DefaultAudioStreamIndex\": {"]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"DefaultSubtitleStreamIndex\": {"]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ETag\": {"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"EncoderPath\": {"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"EncoderProtocol\": {"]
#[doc = "      \"enum\": ["]
#[doc = "        \"File\","]
#[doc = "        \"Http\","]
#[doc = "        \"Rtmp\","]
#[doc = "        \"Rtsp\","]
#[doc = "        \"Udp\","]
#[doc = "        \"Rtp\","]
#[doc = "        \"Ftp\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/MediaProtocol\""]
#[doc = "        }"]
#[doc = "      ],"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"FallbackMaxStreamingBitrate\": {"]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Formats\": {"]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"type\": \"string\""]
#[doc = "      },"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"GenPtsInput\": {"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"HasSegments\": {"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"Id\": {"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"IgnoreDts\": {"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"IgnoreIndex\": {"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"IsInfiniteStream\": {"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"IsRemote\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether the media is remote.\\nDifferentiate internet url vs local network.\","]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"IsoType\": {"]
#[doc = "      \"enum\": ["]
#[doc = "        \"Dvd\","]
#[doc = "        \"BluRay\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/IsoType\""]
#[doc = "        }"]
#[doc = "      ],"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"LiveStreamId\": {"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"MediaAttachments\": {"]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"$ref\": \"#/$defs/MediaAttachment\""]
#[doc = "      },"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"MediaStreams\": {"]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"$ref\": \"#/$defs/MediaStream\""]
#[doc = "      },"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Name\": {"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"OpenToken\": {"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Path\": {"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Protocol\": {"]
#[doc = "      \"enum\": ["]
#[doc = "        \"File\","]
#[doc = "        \"Http\","]
#[doc = "        \"Rtmp\","]
#[doc = "        \"Rtsp\","]
#[doc = "        \"Udp\","]
#[doc = "        \"Rtp\","]
#[doc = "        \"Ftp\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/MediaProtocol\""]
#[doc = "        }"]
#[doc = "      ]"]
#[doc = "    },"]
#[doc = "    \"ReadAtNativeFramerate\": {"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"RequiredHttpHeaders\": {"]
#[doc = "      \"type\": \"object\","]
#[doc = "      \"additionalProperties\": {"]
#[doc = "        \"type\": \"string\","]
#[doc = "        \"nullable\": true"]
#[doc = "      },"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"RequiresClosing\": {"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"RequiresLooping\": {"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"RequiresOpening\": {"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"RunTimeTicks\": {"]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int64\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Size\": {"]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int64\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"SupportsDirectPlay\": {"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"SupportsDirectStream\": {"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"SupportsProbing\": {"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"SupportsTranscoding\": {"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"Timestamp\": {"]
#[doc = "      \"enum\": ["]
#[doc = "        \"None\","]
#[doc = "        \"Zero\","]
#[doc = "        \"Valid\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/TransportStreamTimestamp\""]
#[doc = "        }"]
#[doc = "      ],"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"TranscodingContainer\": {"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"TranscodingSubProtocol\": {"]
#[doc = "      \"description\": \"Media streaming protocol.\\nLowercase for backwards compatibility.\","]
#[doc = "      \"enum\": ["]
#[doc = "        \"http\","]
#[doc = "        \"hls\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/MediaStreamProtocol\""]
#[doc = "        }"]
#[doc = "      ]"]
#[doc = "    },"]
#[doc = "    \"TranscodingUrl\": {"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Type\": {"]
#[doc = "      \"description\": \"The type of a media source.\","]
#[doc = "      \"enum\": ["]
#[doc = "        \"Default\","]
#[doc = "        \"Grouping\","]
#[doc = "        \"Placeholder\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/MediaSourceType\""]
#[doc = "        }"]
#[doc = "      ]"]
#[doc = "    },"]
#[doc = "    \"UseMostCompatibleTranscodingProfile\": {"]
#[doc = "      \"default\": false,"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"Video3DFormat\": {"]
#[doc = "      \"enum\": ["]
#[doc = "        \"HalfSideBySide\","]
#[doc = "        \"FullSideBySide\","]
#[doc = "        \"FullTopAndBottom\","]
#[doc = "        \"HalfTopAndBottom\","]
#[doc = "        \"MVC\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/Video3DFormat\""]
#[doc = "        }"]
#[doc = "      ],"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"VideoType\": {"]
#[doc = "      \"enum\": ["]
#[doc = "        \"VideoFile\","]
#[doc = "        \"Iso\","]
#[doc = "        \"Dvd\","]
#[doc = "        \"BluRay\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/VideoType\""]
#[doc = "        }"]
#[doc = "      ],"]
#[doc = "      \"nullable\": true"]
#[doc = "    }"]
#[doc = "  }"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug, Default)]
pub struct MediaSourceInfo {
    #[serde(
        rename = "AnalyzeDurationMs",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub analyze_duration_ms: ::std::option::Option<i32>,
    #[serde(
        rename = "Bitrate",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub bitrate: ::std::option::Option<i32>,
    #[serde(
        rename = "BufferMs",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub buffer_ms: ::std::option::Option<i32>,
    #[serde(
        rename = "Container",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub container: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "DefaultAudioStreamIndex",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub default_audio_stream_index: ::std::option::Option<i32>,
    #[serde(
        rename = "DefaultSubtitleStreamIndex",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub default_subtitle_stream_index: ::std::option::Option<i32>,
    #[serde(
        rename = "ETag",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub e_tag: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "EncoderPath",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub encoder_path: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "EncoderProtocol",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub encoder_protocol: ::std::option::Option<MediaProtocol>,
    #[serde(
        rename = "FallbackMaxStreamingBitrate",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub fallback_max_streaming_bitrate: ::std::option::Option<i32>,
    #[serde(
        rename = "Formats",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub formats: ::std::vec::Vec<::std::string::String>,
    #[serde(
        rename = "GenPtsInput",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub gen_pts_input: ::std::option::Option<bool>,
    #[serde(
        rename = "HasSegments",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub has_segments: ::std::option::Option<bool>,
    #[serde(
        rename = "Id",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub id: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "IgnoreDts",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub ignore_dts: ::std::option::Option<bool>,
    #[serde(
        rename = "IgnoreIndex",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub ignore_index: ::std::option::Option<bool>,
    #[serde(
        rename = "IsInfiniteStream",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub is_infinite_stream: ::std::option::Option<bool>,
    #[doc = "Gets or sets a value indicating whether the media is remote.\nDifferentiate internet url vs local network."]
    #[serde(
        rename = "IsRemote",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub is_remote: ::std::option::Option<bool>,
    #[serde(
        rename = "IsoType",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub iso_type: ::std::option::Option<IsoType>,
    #[serde(
        rename = "LiveStreamId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub live_stream_id: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "MediaAttachments",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub media_attachments: ::std::vec::Vec<MediaAttachment>,
    #[serde(
        rename = "MediaStreams",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub media_streams: ::std::vec::Vec<MediaStream>,
    #[serde(
        rename = "Name",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub name: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "OpenToken",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub open_token: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "Path",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub path: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "Protocol",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub protocol: ::std::option::Option<MediaProtocol>,
    #[serde(
        rename = "ReadAtNativeFramerate",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub read_at_native_framerate: ::std::option::Option<bool>,
    #[serde(
        rename = "RequiredHttpHeaders",
        default,
        skip_serializing_if = ":: std :: collections :: HashMap::is_empty"
    )]
    pub required_http_headers:
        ::std::collections::HashMap<::std::string::String, ::std::string::String>,
    #[serde(
        rename = "RequiresClosing",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub requires_closing: ::std::option::Option<bool>,
    #[serde(
        rename = "RequiresLooping",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub requires_looping: ::std::option::Option<bool>,
    #[serde(
        rename = "RequiresOpening",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub requires_opening: ::std::option::Option<bool>,
    #[serde(
        rename = "RunTimeTicks",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub run_time_ticks: ::std::option::Option<i64>,
    #[serde(
        rename = "Size",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub size: ::std::option::Option<i64>,
    #[serde(
        rename = "SupportsDirectPlay",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub supports_direct_play: ::std::option::Option<bool>,
    #[serde(
        rename = "SupportsDirectStream",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub supports_direct_stream: ::std::option::Option<bool>,
    #[serde(
        rename = "SupportsProbing",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub supports_probing: ::std::option::Option<bool>,
    #[serde(
        rename = "SupportsTranscoding",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub supports_transcoding: ::std::option::Option<bool>,
    #[serde(
        rename = "Timestamp",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub timestamp: ::std::option::Option<TransportStreamTimestamp>,
    #[serde(
        rename = "TranscodingContainer",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub transcoding_container: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "TranscodingSubProtocol",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub transcoding_sub_protocol: ::std::option::Option<MediaStreamProtocol>,
    #[serde(
        rename = "TranscodingUrl",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub transcoding_url: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "Type",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub type_: ::std::option::Option<MediaSourceType>,
    #[serde(rename = "UseMostCompatibleTranscodingProfile", default)]
    pub use_most_compatible_transcoding_profile: bool,
    #[serde(
        rename = "Video3DFormat",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub video3_d_format: ::std::option::Option<Video3DFormat>,
    #[serde(
        rename = "VideoType",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub video_type: ::std::option::Option<VideoType>,
}
#[doc = "The type of a media source."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"The type of a media source.\","]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"Default\","]
#[doc = "    \"Grouping\","]
#[doc = "    \"Placeholder\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum MediaSourceType {
    Default,
    Grouping,
    Placeholder,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for MediaSourceType {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::Default => f.write_str("Default"),
            Self::Grouping => f.write_str("Grouping"),
            Self::Placeholder => f.write_str("Placeholder"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for MediaSourceType {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "Default" => Ok(Self::Default),
            "Grouping" => Ok(Self::Grouping),
            "Placeholder" => Ok(Self::Placeholder),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for MediaSourceType {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for MediaSourceType {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for MediaSourceType {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "Class MediaStream."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"Class MediaStream.\","]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"properties\": {"]
#[doc = "    \"AspectRatio\": {"]
#[doc = "      \"description\": \"Gets or sets the aspect ratio.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"AudioSpatialFormat\": {"]
#[doc = "      \"description\": \"Gets the audio spatial format.\","]
#[doc = "      \"default\": \"None\","]
#[doc = "      \"readOnly\": true,"]
#[doc = "      \"enum\": ["]
#[doc = "        \"None\","]
#[doc = "        \"DolbyAtmos\","]
#[doc = "        \"DTSX\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/AudioSpatialFormat\""]
#[doc = "        }"]
#[doc = "      ]"]
#[doc = "    },"]
#[doc = "    \"AverageFrameRate\": {"]
#[doc = "      \"description\": \"Gets or sets the average frame rate.\","]
#[doc = "      \"type\": \"number\","]
#[doc = "      \"format\": \"float\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"BitDepth\": {"]
#[doc = "      \"description\": \"Gets or sets the bit depth.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"BitRate\": {"]
#[doc = "      \"description\": \"Gets or sets the bit rate.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"BlPresentFlag\": {"]
#[doc = "      \"description\": \"Gets or sets the Dolby Vision bl present flag.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ChannelLayout\": {"]
#[doc = "      \"description\": \"Gets or sets the channel layout.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Channels\": {"]
#[doc = "      \"description\": \"Gets or sets the channels.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Codec\": {"]
#[doc = "      \"description\": \"Gets or sets the codec.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"CodecTag\": {"]
#[doc = "      \"description\": \"Gets or sets the codec tag.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"CodecTimeBase\": {"]
#[doc = "      \"description\": \"Gets or sets the codec time base.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ColorPrimaries\": {"]
#[doc = "      \"description\": \"Gets or sets the color primaries.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ColorRange\": {"]
#[doc = "      \"description\": \"Gets or sets the color range.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ColorSpace\": {"]
#[doc = "      \"description\": \"Gets or sets the color space.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ColorTransfer\": {"]
#[doc = "      \"description\": \"Gets or sets the color transfer.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Comment\": {"]
#[doc = "      \"description\": \"Gets or sets the comment.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"DeliveryMethod\": {"]
#[doc = "      \"description\": \"Gets or sets the method.\","]
#[doc = "      \"enum\": ["]
#[doc = "        \"Encode\","]
#[doc = "        \"Embed\","]
#[doc = "        \"External\","]
#[doc = "        \"Hls\","]
#[doc = "        \"Drop\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/SubtitleDeliveryMethod\""]
#[doc = "        }"]
#[doc = "      ],"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"DeliveryUrl\": {"]
#[doc = "      \"description\": \"Gets or sets the delivery URL.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"DisplayTitle\": {"]
#[doc = "      \"readOnly\": true,"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"DvBlSignalCompatibilityId\": {"]
#[doc = "      \"description\": \"Gets or sets the Dolby Vision bl signal compatibility id.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"DvLevel\": {"]
#[doc = "      \"description\": \"Gets or sets the Dolby Vision level.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"DvProfile\": {"]
#[doc = "      \"description\": \"Gets or sets the Dolby Vision profile.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"DvVersionMajor\": {"]
#[doc = "      \"description\": \"Gets or sets the Dolby Vision version major.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"DvVersionMinor\": {"]
#[doc = "      \"description\": \"Gets or sets the Dolby Vision version minor.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ElPresentFlag\": {"]
#[doc = "      \"description\": \"Gets or sets the Dolby Vision el present flag.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Hdr10PlusPresentFlag\": {"]
#[doc = "      \"type\": \"boolean\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Height\": {"]
#[doc = "      \"description\": \"Gets or sets the height.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Index\": {"]
#[doc = "      \"description\": \"Gets or sets the index.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\""]
#[doc = "    },"]
#[doc = "    \"IsAVC\": {"]
#[doc = "      \"type\": \"boolean\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"IsAnamorphic\": {"]
#[doc = "      \"description\": \"Gets or sets whether this instance is anamorphic.\","]
#[doc = "      \"type\": \"boolean\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"IsDefault\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether this instance is default.\","]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"IsExternal\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether this instance is external.\","]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"IsExternalUrl\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether this instance is external URL.\","]
#[doc = "      \"type\": \"boolean\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"IsForced\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether this instance is forced.\","]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"IsHearingImpaired\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether this instance is for the hearing impaired.\","]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"IsInterlaced\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether this instance is interlaced.\","]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"IsOriginal\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether this instance is original.\","]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"IsTextSubtitleStream\": {"]
#[doc = "      \"readOnly\": true,"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"Language\": {"]
#[doc = "      \"description\": \"Gets or sets the language.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Level\": {"]
#[doc = "      \"description\": \"Gets or sets the level.\","]
#[doc = "      \"type\": \"number\","]
#[doc = "      \"format\": \"double\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"LocalizedDefault\": {"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"LocalizedExternal\": {"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"LocalizedForced\": {"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"LocalizedHearingImpaired\": {"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"LocalizedLanguage\": {"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"LocalizedOriginal\": {"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"LocalizedUndefined\": {"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"NalLengthSize\": {"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"PacketLength\": {"]
#[doc = "      \"description\": \"Gets or sets the length of the packet.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Path\": {"]
#[doc = "      \"description\": \"Gets or sets the filename.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"PixelFormat\": {"]
#[doc = "      \"description\": \"Gets or sets the pixel format.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Profile\": {"]
#[doc = "      \"description\": \"Gets or sets the profile.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"RealFrameRate\": {"]
#[doc = "      \"description\": \"Gets or sets the real frame rate.\","]
#[doc = "      \"type\": \"number\","]
#[doc = "      \"format\": \"float\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"RefFrames\": {"]
#[doc = "      \"description\": \"Gets or sets the reference frames.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ReferenceFrameRate\": {"]
#[doc = "      \"description\": \"Gets the framerate used as reference.\\nPrefer AverageFrameRate, if that is null or an unrealistic value\\nthen fallback to RealFrameRate.\","]
#[doc = "      \"readOnly\": true,"]
#[doc = "      \"type\": \"number\","]
#[doc = "      \"format\": \"float\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Rotation\": {"]
#[doc = "      \"description\": \"Gets or sets the Rotation in degrees.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"RpuPresentFlag\": {"]
#[doc = "      \"description\": \"Gets or sets the Dolby Vision rpu present flag.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"SampleRate\": {"]
#[doc = "      \"description\": \"Gets or sets the sample rate.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Score\": {"]
#[doc = "      \"description\": \"Gets or sets the score.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"SupportsExternalStream\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether [supports external stream].\","]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"TimeBase\": {"]
#[doc = "      \"description\": \"Gets or sets the time base.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Title\": {"]
#[doc = "      \"description\": \"Gets or sets the title.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Type\": {"]
#[doc = "      \"description\": \"Gets or sets the type.\","]
#[doc = "      \"enum\": ["]
#[doc = "        \"Audio\","]
#[doc = "        \"Video\","]
#[doc = "        \"Subtitle\","]
#[doc = "        \"EmbeddedImage\","]
#[doc = "        \"Data\","]
#[doc = "        \"Lyric\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/MediaStreamType\""]
#[doc = "        }"]
#[doc = "      ]"]
#[doc = "    },"]
#[doc = "    \"VideoDoViTitle\": {"]
#[doc = "      \"description\": \"Gets the video dovi title.\","]
#[doc = "      \"readOnly\": true,"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"VideoRange\": {"]
#[doc = "      \"description\": \"Gets the video range.\","]
#[doc = "      \"default\": \"Unknown\","]
#[doc = "      \"readOnly\": true,"]
#[doc = "      \"enum\": ["]
#[doc = "        \"Unknown\","]
#[doc = "        \"SDR\","]
#[doc = "        \"HDR\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/VideoRange\""]
#[doc = "        }"]
#[doc = "      ]"]
#[doc = "    },"]
#[doc = "    \"VideoRangeType\": {"]
#[doc = "      \"description\": \"Gets the video range type.\","]
#[doc = "      \"default\": \"Unknown\","]
#[doc = "      \"readOnly\": true,"]
#[doc = "      \"enum\": ["]
#[doc = "        \"Unknown\","]
#[doc = "        \"SDR\","]
#[doc = "        \"HDR10\","]
#[doc = "        \"HLG\","]
#[doc = "        \"DOVI\","]
#[doc = "        \"DOVIWithHDR10\","]
#[doc = "        \"DOVIWithHLG\","]
#[doc = "        \"DOVIWithSDR\","]
#[doc = "        \"DOVIWithEL\","]
#[doc = "        \"DOVIWithHDR10Plus\","]
#[doc = "        \"DOVIWithELHDR10Plus\","]
#[doc = "        \"DOVIInvalid\","]
#[doc = "        \"HDR10Plus\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/VideoRangeType\""]
#[doc = "        }"]
#[doc = "      ]"]
#[doc = "    },"]
#[doc = "    \"Width\": {"]
#[doc = "      \"description\": \"Gets or sets the width.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    }"]
#[doc = "  }"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug, Default)]
pub struct MediaStream {
    #[doc = "Gets or sets the aspect ratio."]
    #[serde(
        rename = "AspectRatio",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub aspect_ratio: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "AudioSpatialFormat",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub audio_spatial_format: ::std::option::Option<AudioSpatialFormat>,
    #[doc = "Gets or sets the average frame rate."]
    #[serde(
        rename = "AverageFrameRate",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub average_frame_rate: ::std::option::Option<f32>,
    #[doc = "Gets or sets the bit depth."]
    #[serde(
        rename = "BitDepth",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub bit_depth: ::std::option::Option<i32>,
    #[doc = "Gets or sets the bit rate."]
    #[serde(
        rename = "BitRate",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub bit_rate: ::std::option::Option<i32>,
    #[doc = "Gets or sets the Dolby Vision bl present flag."]
    #[serde(
        rename = "BlPresentFlag",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub bl_present_flag: ::std::option::Option<i32>,
    #[doc = "Gets or sets the channel layout."]
    #[serde(
        rename = "ChannelLayout",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub channel_layout: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the channels."]
    #[serde(
        rename = "Channels",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub channels: ::std::option::Option<i32>,
    #[doc = "Gets or sets the codec."]
    #[serde(
        rename = "Codec",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub codec: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the codec tag."]
    #[serde(
        rename = "CodecTag",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub codec_tag: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the codec time base."]
    #[serde(
        rename = "CodecTimeBase",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub codec_time_base: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the color primaries."]
    #[serde(
        rename = "ColorPrimaries",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub color_primaries: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the color range."]
    #[serde(
        rename = "ColorRange",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub color_range: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the color space."]
    #[serde(
        rename = "ColorSpace",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub color_space: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the color transfer."]
    #[serde(
        rename = "ColorTransfer",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub color_transfer: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the comment."]
    #[serde(
        rename = "Comment",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub comment: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "DeliveryMethod",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub delivery_method: ::std::option::Option<SubtitleDeliveryMethod>,
    #[doc = "Gets or sets the delivery URL."]
    #[serde(
        rename = "DeliveryUrl",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub delivery_url: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "DisplayTitle",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub display_title: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the Dolby Vision bl signal compatibility id."]
    #[serde(
        rename = "DvBlSignalCompatibilityId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub dv_bl_signal_compatibility_id: ::std::option::Option<i32>,
    #[doc = "Gets or sets the Dolby Vision level."]
    #[serde(
        rename = "DvLevel",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub dv_level: ::std::option::Option<i32>,
    #[doc = "Gets or sets the Dolby Vision profile."]
    #[serde(
        rename = "DvProfile",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub dv_profile: ::std::option::Option<i32>,
    #[doc = "Gets or sets the Dolby Vision version major."]
    #[serde(
        rename = "DvVersionMajor",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub dv_version_major: ::std::option::Option<i32>,
    #[doc = "Gets or sets the Dolby Vision version minor."]
    #[serde(
        rename = "DvVersionMinor",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub dv_version_minor: ::std::option::Option<i32>,
    #[doc = "Gets or sets the Dolby Vision el present flag."]
    #[serde(
        rename = "ElPresentFlag",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub el_present_flag: ::std::option::Option<i32>,
    #[serde(
        rename = "Hdr10PlusPresentFlag",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub hdr10_plus_present_flag: ::std::option::Option<bool>,
    #[doc = "Gets or sets the height."]
    #[serde(
        rename = "Height",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub height: ::std::option::Option<i32>,
    #[doc = "Gets or sets the index."]
    #[serde(
        rename = "Index",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub index: ::std::option::Option<i32>,
    #[doc = "Gets or sets whether this instance is anamorphic."]
    #[serde(
        rename = "IsAnamorphic",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub is_anamorphic: ::std::option::Option<bool>,
    #[serde(
        rename = "IsAVC",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub is_avc: ::std::option::Option<bool>,
    #[doc = "Gets or sets a value indicating whether this instance is default."]
    #[serde(
        rename = "IsDefault",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub is_default: ::std::option::Option<bool>,
    #[doc = "Gets or sets a value indicating whether this instance is external."]
    #[serde(
        rename = "IsExternal",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub is_external: ::std::option::Option<bool>,
    #[doc = "Gets or sets a value indicating whether this instance is external URL."]
    #[serde(
        rename = "IsExternalUrl",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub is_external_url: ::std::option::Option<bool>,
    #[doc = "Gets or sets a value indicating whether this instance is forced."]
    #[serde(
        rename = "IsForced",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub is_forced: ::std::option::Option<bool>,
    #[doc = "Gets or sets a value indicating whether this instance is for the hearing impaired."]
    #[serde(
        rename = "IsHearingImpaired",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub is_hearing_impaired: ::std::option::Option<bool>,
    #[doc = "Gets or sets a value indicating whether this instance is interlaced."]
    #[serde(
        rename = "IsInterlaced",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub is_interlaced: ::std::option::Option<bool>,
    #[doc = "Gets or sets a value indicating whether this instance is original."]
    #[serde(
        rename = "IsOriginal",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub is_original: ::std::option::Option<bool>,
    #[serde(
        rename = "IsTextSubtitleStream",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub is_text_subtitle_stream: ::std::option::Option<bool>,
    #[doc = "Gets or sets the language."]
    #[serde(
        rename = "Language",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub language: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the level."]
    #[serde(
        rename = "Level",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub level: ::std::option::Option<f64>,
    #[serde(
        rename = "LocalizedDefault",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub localized_default: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "LocalizedExternal",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub localized_external: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "LocalizedForced",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub localized_forced: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "LocalizedHearingImpaired",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub localized_hearing_impaired: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "LocalizedLanguage",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub localized_language: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "LocalizedOriginal",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub localized_original: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "LocalizedUndefined",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub localized_undefined: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "NalLengthSize",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub nal_length_size: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the length of the packet."]
    #[serde(
        rename = "PacketLength",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub packet_length: ::std::option::Option<i32>,
    #[doc = "Gets or sets the filename."]
    #[serde(
        rename = "Path",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub path: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the pixel format."]
    #[serde(
        rename = "PixelFormat",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub pixel_format: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the profile."]
    #[serde(
        rename = "Profile",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub profile: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the real frame rate."]
    #[serde(
        rename = "RealFrameRate",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub real_frame_rate: ::std::option::Option<f32>,
    #[doc = "Gets or sets the reference frames."]
    #[serde(
        rename = "RefFrames",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub ref_frames: ::std::option::Option<i32>,
    #[doc = "Gets the framerate used as reference.\nPrefer AverageFrameRate, if that is null or an unrealistic value\nthen fallback to RealFrameRate."]
    #[serde(
        rename = "ReferenceFrameRate",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub reference_frame_rate: ::std::option::Option<f32>,
    #[doc = "Gets or sets the Rotation in degrees."]
    #[serde(
        rename = "Rotation",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub rotation: ::std::option::Option<i32>,
    #[doc = "Gets or sets the Dolby Vision rpu present flag."]
    #[serde(
        rename = "RpuPresentFlag",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub rpu_present_flag: ::std::option::Option<i32>,
    #[doc = "Gets or sets the sample rate."]
    #[serde(
        rename = "SampleRate",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub sample_rate: ::std::option::Option<i32>,
    #[doc = "Gets or sets the score."]
    #[serde(
        rename = "Score",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub score: ::std::option::Option<i32>,
    #[doc = "Gets or sets a value indicating whether [supports external stream]."]
    #[serde(
        rename = "SupportsExternalStream",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub supports_external_stream: ::std::option::Option<bool>,
    #[doc = "Gets or sets the time base."]
    #[serde(
        rename = "TimeBase",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub time_base: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the title."]
    #[serde(
        rename = "Title",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub title: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "Type",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub type_: ::std::option::Option<MediaStreamType>,
    #[doc = "Gets the video dovi title."]
    #[serde(
        rename = "VideoDoViTitle",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub video_do_vi_title: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "VideoRange",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub video_range: ::std::option::Option<VideoRange>,
    #[serde(
        rename = "VideoRangeType",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub video_range_type: ::std::option::Option<VideoRangeType>,
    #[doc = "Gets or sets the width."]
    #[serde(
        rename = "Width",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub width: ::std::option::Option<i32>,
}
#[doc = "Media streaming protocol.\nLowercase for backwards compatibility."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"Media streaming protocol.\\nLowercase for backwards compatibility.\","]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"http\","]
#[doc = "    \"hls\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum MediaStreamProtocol {
    #[serde(rename = "http")]
    Http,
    #[serde(rename = "hls")]
    Hls,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for MediaStreamProtocol {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::Http => f.write_str("http"),
            Self::Hls => f.write_str("hls"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for MediaStreamProtocol {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "http" => Ok(Self::Http),
            "hls" => Ok(Self::Hls),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for MediaStreamProtocol {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for MediaStreamProtocol {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for MediaStreamProtocol {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "Enum MediaStreamType."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"Enum MediaStreamType.\","]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"Audio\","]
#[doc = "    \"Video\","]
#[doc = "    \"Subtitle\","]
#[doc = "    \"EmbeddedImage\","]
#[doc = "    \"Data\","]
#[doc = "    \"Lyric\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum MediaStreamType {
    Audio,
    Video,
    Subtitle,
    EmbeddedImage,
    Data,
    Lyric,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for MediaStreamType {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::Audio => f.write_str("Audio"),
            Self::Video => f.write_str("Video"),
            Self::Subtitle => f.write_str("Subtitle"),
            Self::EmbeddedImage => f.write_str("EmbeddedImage"),
            Self::Data => f.write_str("Data"),
            Self::Lyric => f.write_str("Lyric"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for MediaStreamType {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "Audio" => Ok(Self::Audio),
            "Video" => Ok(Self::Video),
            "Subtitle" => Ok(Self::Subtitle),
            "EmbeddedImage" => Ok(Self::EmbeddedImage),
            "Data" => Ok(Self::Data),
            "Lyric" => Ok(Self::Lyric),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for MediaStreamType {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for MediaStreamType {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for MediaStreamType {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "Media types."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"Media types.\","]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"Unknown\","]
#[doc = "    \"Video\","]
#[doc = "    \"Audio\","]
#[doc = "    \"Photo\","]
#[doc = "    \"Book\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum MediaType {
    Unknown,
    Video,
    Audio,
    Photo,
    Book,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for MediaType {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::Unknown => f.write_str("Unknown"),
            Self::Video => f.write_str("Video"),
            Self::Audio => f.write_str("Audio"),
            Self::Photo => f.write_str("Photo"),
            Self::Book => f.write_str("Book"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for MediaType {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "Unknown" => Ok(Self::Unknown),
            "Video" => Ok(Self::Video),
            "Audio" => Ok(Self::Audio),
            "Photo" => Ok(Self::Photo),
            "Book" => Ok(Self::Book),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for MediaType {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for MediaType {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for MediaType {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "`MediaUrl`"]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"properties\": {"]
#[doc = "    \"Name\": {"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Url\": {"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    }"]
#[doc = "  }"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug, Default)]
pub struct MediaUrl {
    #[serde(
        rename = "Name",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub name: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "Url",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub url: ::std::option::Option<::std::string::String>,
}
#[doc = "Enum MetadataFields."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"Enum MetadataFields.\","]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"Cast\","]
#[doc = "    \"Genres\","]
#[doc = "    \"ProductionLocations\","]
#[doc = "    \"Studios\","]
#[doc = "    \"Tags\","]
#[doc = "    \"Name\","]
#[doc = "    \"Overview\","]
#[doc = "    \"Runtime\","]
#[doc = "    \"OfficialRating\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum MetadataField {
    Cast,
    Genres,
    ProductionLocations,
    Studios,
    Tags,
    Name,
    Overview,
    Runtime,
    OfficialRating,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for MetadataField {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::Cast => f.write_str("Cast"),
            Self::Genres => f.write_str("Genres"),
            Self::ProductionLocations => f.write_str("ProductionLocations"),
            Self::Studios => f.write_str("Studios"),
            Self::Tags => f.write_str("Tags"),
            Self::Name => f.write_str("Name"),
            Self::Overview => f.write_str("Overview"),
            Self::Runtime => f.write_str("Runtime"),
            Self::OfficialRating => f.write_str("OfficialRating"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for MetadataField {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "Cast" => Ok(Self::Cast),
            "Genres" => Ok(Self::Genres),
            "ProductionLocations" => Ok(Self::ProductionLocations),
            "Studios" => Ok(Self::Studios),
            "Tags" => Ok(Self::Tags),
            "Name" => Ok(Self::Name),
            "Overview" => Ok(Self::Overview),
            "Runtime" => Ok(Self::Runtime),
            "OfficialRating" => Ok(Self::OfficialRating),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for MetadataField {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for MetadataField {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for MetadataField {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "`NameGuidPair`"]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"properties\": {"]
#[doc = "    \"Id\": {"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"format\": \"uuid\""]
#[doc = "    },"]
#[doc = "    \"Name\": {"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    }"]
#[doc = "  }"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug, Default)]
pub struct NameGuidPair {
    #[serde(
        rename = "Id",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub id: ::std::option::Option<::uuid::Uuid>,
    #[serde(
        rename = "Name",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub name: ::std::option::Option<::std::string::String>,
}
#[doc = "The person kind."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"The person kind.\","]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"Unknown\","]
#[doc = "    \"Actor\","]
#[doc = "    \"Director\","]
#[doc = "    \"Composer\","]
#[doc = "    \"Writer\","]
#[doc = "    \"GuestStar\","]
#[doc = "    \"Producer\","]
#[doc = "    \"Conductor\","]
#[doc = "    \"Lyricist\","]
#[doc = "    \"Arranger\","]
#[doc = "    \"Engineer\","]
#[doc = "    \"Mixer\","]
#[doc = "    \"Remixer\","]
#[doc = "    \"Creator\","]
#[doc = "    \"Artist\","]
#[doc = "    \"AlbumArtist\","]
#[doc = "    \"Author\","]
#[doc = "    \"Illustrator\","]
#[doc = "    \"Penciller\","]
#[doc = "    \"Inker\","]
#[doc = "    \"Colorist\","]
#[doc = "    \"Letterer\","]
#[doc = "    \"CoverArtist\","]
#[doc = "    \"Editor\","]
#[doc = "    \"Translator\","]
#[doc = "    \"Narrator\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum PersonKind {
    Unknown,
    Actor,
    Director,
    Composer,
    Writer,
    GuestStar,
    Producer,
    Conductor,
    Lyricist,
    Arranger,
    Engineer,
    Mixer,
    Remixer,
    Creator,
    Artist,
    AlbumArtist,
    Author,
    Illustrator,
    Penciller,
    Inker,
    Colorist,
    Letterer,
    CoverArtist,
    Editor,
    Translator,
    Narrator,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for PersonKind {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::Unknown => f.write_str("Unknown"),
            Self::Actor => f.write_str("Actor"),
            Self::Director => f.write_str("Director"),
            Self::Composer => f.write_str("Composer"),
            Self::Writer => f.write_str("Writer"),
            Self::GuestStar => f.write_str("GuestStar"),
            Self::Producer => f.write_str("Producer"),
            Self::Conductor => f.write_str("Conductor"),
            Self::Lyricist => f.write_str("Lyricist"),
            Self::Arranger => f.write_str("Arranger"),
            Self::Engineer => f.write_str("Engineer"),
            Self::Mixer => f.write_str("Mixer"),
            Self::Remixer => f.write_str("Remixer"),
            Self::Creator => f.write_str("Creator"),
            Self::Artist => f.write_str("Artist"),
            Self::AlbumArtist => f.write_str("AlbumArtist"),
            Self::Author => f.write_str("Author"),
            Self::Illustrator => f.write_str("Illustrator"),
            Self::Penciller => f.write_str("Penciller"),
            Self::Inker => f.write_str("Inker"),
            Self::Colorist => f.write_str("Colorist"),
            Self::Letterer => f.write_str("Letterer"),
            Self::CoverArtist => f.write_str("CoverArtist"),
            Self::Editor => f.write_str("Editor"),
            Self::Translator => f.write_str("Translator"),
            Self::Narrator => f.write_str("Narrator"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for PersonKind {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "Unknown" => Ok(Self::Unknown),
            "Actor" => Ok(Self::Actor),
            "Director" => Ok(Self::Director),
            "Composer" => Ok(Self::Composer),
            "Writer" => Ok(Self::Writer),
            "GuestStar" => Ok(Self::GuestStar),
            "Producer" => Ok(Self::Producer),
            "Conductor" => Ok(Self::Conductor),
            "Lyricist" => Ok(Self::Lyricist),
            "Arranger" => Ok(Self::Arranger),
            "Engineer" => Ok(Self::Engineer),
            "Mixer" => Ok(Self::Mixer),
            "Remixer" => Ok(Self::Remixer),
            "Creator" => Ok(Self::Creator),
            "Artist" => Ok(Self::Artist),
            "AlbumArtist" => Ok(Self::AlbumArtist),
            "Author" => Ok(Self::Author),
            "Illustrator" => Ok(Self::Illustrator),
            "Penciller" => Ok(Self::Penciller),
            "Inker" => Ok(Self::Inker),
            "Colorist" => Ok(Self::Colorist),
            "Letterer" => Ok(Self::Letterer),
            "CoverArtist" => Ok(Self::CoverArtist),
            "Editor" => Ok(Self::Editor),
            "Translator" => Ok(Self::Translator),
            "Narrator" => Ok(Self::Narrator),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for PersonKind {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for PersonKind {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for PersonKind {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "The play access of an item."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"The play access of an item.\","]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"Full\","]
#[doc = "    \"None\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum PlayAccess {
    Full,
    None,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for PlayAccess {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::Full => f.write_str("Full"),
            Self::None => f.write_str("None"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for PlayAccess {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "Full" => Ok(Self::Full),
            "None" => Ok(Self::None),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for PlayAccess {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for PlayAccess {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for PlayAccess {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "The play method."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"The play method.\","]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"Transcode\","]
#[doc = "    \"DirectStream\","]
#[doc = "    \"DirectPlay\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum PlayMethod {
    Transcode,
    DirectStream,
    DirectPlay,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for PlayMethod {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::Transcode => f.write_str("Transcode"),
            Self::DirectStream => f.write_str("DirectStream"),
            Self::DirectPlay => f.write_str("DirectPlay"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for PlayMethod {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "Transcode" => Ok(Self::Transcode),
            "DirectStream" => Ok(Self::DirectStream),
            "DirectPlay" => Ok(Self::DirectPlay),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for PlayMethod {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for PlayMethod {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for PlayMethod {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "The playback error code."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"The playback error code.\","]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"NotAllowed\","]
#[doc = "    \"NoCompatibleStream\","]
#[doc = "    \"RateLimitExceeded\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum PlaybackErrorCode {
    NotAllowed,
    NoCompatibleStream,
    RateLimitExceeded,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for PlaybackErrorCode {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::NotAllowed => f.write_str("NotAllowed"),
            Self::NoCompatibleStream => f.write_str("NoCompatibleStream"),
            Self::RateLimitExceeded => f.write_str("RateLimitExceeded"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for PlaybackErrorCode {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "NotAllowed" => Ok(Self::NotAllowed),
            "NoCompatibleStream" => Ok(Self::NoCompatibleStream),
            "RateLimitExceeded" => Ok(Self::RateLimitExceeded),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for PlaybackErrorCode {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for PlaybackErrorCode {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for PlaybackErrorCode {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "Playback info dto."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"Playback info dto.\","]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"properties\": {"]
#[doc = "    \"AllowAudioStreamCopy\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether to allow audio stream copy.\","]
#[doc = "      \"type\": \"boolean\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"AllowVideoStreamCopy\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether to enable video stream copy.\","]
#[doc = "      \"type\": \"boolean\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"AlwaysBurnInSubtitleWhenTranscoding\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether always burn in subtitles when transcoding.\","]
#[doc = "      \"type\": \"boolean\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"AudioStreamIndex\": {"]
#[doc = "      \"description\": \"Gets or sets the audio stream index.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"AutoOpenLiveStream\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether to auto open the live stream.\","]
#[doc = "      \"type\": \"boolean\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"DeviceProfile\": {"]
#[doc = "      \"description\": \"Gets or sets the device profile.\","]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/DeviceProfile\""]
#[doc = "        }"]
#[doc = "      ],"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"EnableDirectPlay\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether to enable direct play.\","]
#[doc = "      \"type\": \"boolean\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"EnableDirectStream\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether to enable direct stream.\","]
#[doc = "      \"type\": \"boolean\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"EnableTranscoding\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether to enable transcoding.\","]
#[doc = "      \"type\": \"boolean\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"LiveStreamId\": {"]
#[doc = "      \"description\": \"Gets or sets the live stream id.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"MaxAudioChannels\": {"]
#[doc = "      \"description\": \"Gets or sets the max audio channels.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"MaxStreamingBitrate\": {"]
#[doc = "      \"description\": \"Gets or sets the max streaming bitrate.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"MediaSourceId\": {"]
#[doc = "      \"description\": \"Gets or sets the media source id.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"StartTimeTicks\": {"]
#[doc = "      \"description\": \"Gets or sets the start time in ticks.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int64\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"SubtitleStreamIndex\": {"]
#[doc = "      \"description\": \"Gets or sets the subtitle stream index.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"UserId\": {"]
#[doc = "      \"description\": \"Gets or sets the playback userId.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"format\": \"uuid\","]
#[doc = "      \"nullable\": true"]
#[doc = "    }"]
#[doc = "  }"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug, Default)]
pub struct PlaybackInfoDto {
    #[doc = "Gets or sets a value indicating whether to allow audio stream copy."]
    #[serde(
        rename = "AllowAudioStreamCopy",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub allow_audio_stream_copy: ::std::option::Option<bool>,
    #[doc = "Gets or sets a value indicating whether to enable video stream copy."]
    #[serde(
        rename = "AllowVideoStreamCopy",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub allow_video_stream_copy: ::std::option::Option<bool>,
    #[doc = "Gets or sets a value indicating whether always burn in subtitles when transcoding."]
    #[serde(
        rename = "AlwaysBurnInSubtitleWhenTranscoding",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub always_burn_in_subtitle_when_transcoding: ::std::option::Option<bool>,
    #[doc = "Gets or sets the audio stream index."]
    #[serde(
        rename = "AudioStreamIndex",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub audio_stream_index: ::std::option::Option<i32>,
    #[doc = "Gets or sets a value indicating whether to auto open the live stream."]
    #[serde(
        rename = "AutoOpenLiveStream",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub auto_open_live_stream: ::std::option::Option<bool>,
    #[doc = "Gets or sets the device profile."]
    #[serde(
        rename = "DeviceProfile",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub device_profile: ::std::option::Option<DeviceProfile>,
    #[doc = "Gets or sets a value indicating whether to enable direct play."]
    #[serde(
        rename = "EnableDirectPlay",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub enable_direct_play: ::std::option::Option<bool>,
    #[doc = "Gets or sets a value indicating whether to enable direct stream."]
    #[serde(
        rename = "EnableDirectStream",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub enable_direct_stream: ::std::option::Option<bool>,
    #[doc = "Gets or sets a value indicating whether to enable transcoding."]
    #[serde(
        rename = "EnableTranscoding",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub enable_transcoding: ::std::option::Option<bool>,
    #[doc = "Gets or sets the live stream id."]
    #[serde(
        rename = "LiveStreamId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub live_stream_id: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the max audio channels."]
    #[serde(
        rename = "MaxAudioChannels",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub max_audio_channels: ::std::option::Option<i32>,
    #[doc = "Gets or sets the max streaming bitrate."]
    #[serde(
        rename = "MaxStreamingBitrate",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub max_streaming_bitrate: ::std::option::Option<i32>,
    #[doc = "Gets or sets the media source id."]
    #[serde(
        rename = "MediaSourceId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub media_source_id: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the start time in ticks."]
    #[serde(
        rename = "StartTimeTicks",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub start_time_ticks: ::std::option::Option<i64>,
    #[doc = "Gets or sets the subtitle stream index."]
    #[serde(
        rename = "SubtitleStreamIndex",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub subtitle_stream_index: ::std::option::Option<i32>,
    #[doc = "Gets or sets the playback userId."]
    #[serde(
        rename = "UserId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub user_id: ::std::option::Option<::uuid::Uuid>,
}
#[doc = "Class PlaybackInfoResponse."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"Class PlaybackInfoResponse.\","]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"properties\": {"]
#[doc = "    \"ErrorCode\": {"]
#[doc = "      \"description\": \"Gets or sets the error code.\","]
#[doc = "      \"enum\": ["]
#[doc = "        \"NotAllowed\","]
#[doc = "        \"NoCompatibleStream\","]
#[doc = "        \"RateLimitExceeded\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/PlaybackErrorCode\""]
#[doc = "        }"]
#[doc = "      ],"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"MediaSources\": {"]
#[doc = "      \"description\": \"Gets or sets the media sources.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"$ref\": \"#/$defs/MediaSourceInfo\""]
#[doc = "      }"]
#[doc = "    },"]
#[doc = "    \"PlaySessionId\": {"]
#[doc = "      \"description\": \"Gets or sets the play session identifier.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    }"]
#[doc = "  }"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug, Default)]
pub struct PlaybackInfoResponse {
    #[serde(
        rename = "ErrorCode",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub error_code: ::std::option::Option<PlaybackErrorCode>,
    #[doc = "Gets or sets the media sources."]
    #[serde(
        rename = "MediaSources",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub media_sources: ::std::vec::Vec<MediaSourceInfo>,
    #[doc = "Gets or sets the play session identifier."]
    #[serde(
        rename = "PlaySessionId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub play_session_id: ::std::option::Option<::std::string::String>,
}
#[doc = "Enum PlaybackOrder."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"Enum PlaybackOrder.\","]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"Default\","]
#[doc = "    \"Shuffle\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum PlaybackOrder {
    Default,
    Shuffle,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for PlaybackOrder {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::Default => f.write_str("Default"),
            Self::Shuffle => f.write_str("Shuffle"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for PlaybackOrder {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "Default" => Ok(Self::Default),
            "Shuffle" => Ok(Self::Shuffle),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for PlaybackOrder {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for PlaybackOrder {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for PlaybackOrder {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "`PlayerStateInfo`"]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"properties\": {"]
#[doc = "    \"AudioStreamIndex\": {"]
#[doc = "      \"description\": \"Gets or sets the index of the now playing audio stream.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"CanSeek\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether this instance can seek.\","]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"IsMuted\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether this instance is muted.\","]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"IsPaused\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether this instance is paused.\","]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"LiveStreamId\": {"]
#[doc = "      \"description\": \"Gets or sets the now playing live stream identifier.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"MediaSourceId\": {"]
#[doc = "      \"description\": \"Gets or sets the now playing media version identifier.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"PlayMethod\": {"]
#[doc = "      \"description\": \"Gets or sets the play method.\","]
#[doc = "      \"enum\": ["]
#[doc = "        \"Transcode\","]
#[doc = "        \"DirectStream\","]
#[doc = "        \"DirectPlay\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/PlayMethod\""]
#[doc = "        }"]
#[doc = "      ],"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"PlaybackOrder\": {"]
#[doc = "      \"description\": \"Gets or sets the playback order.\","]
#[doc = "      \"enum\": ["]
#[doc = "        \"Default\","]
#[doc = "        \"Shuffle\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/PlaybackOrder\""]
#[doc = "        }"]
#[doc = "      ]"]
#[doc = "    },"]
#[doc = "    \"PositionTicks\": {"]
#[doc = "      \"description\": \"Gets or sets the now playing position ticks.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int64\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"RepeatMode\": {"]
#[doc = "      \"description\": \"Gets or sets the repeat mode.\","]
#[doc = "      \"enum\": ["]
#[doc = "        \"RepeatNone\","]
#[doc = "        \"RepeatAll\","]
#[doc = "        \"RepeatOne\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/RepeatMode\""]
#[doc = "        }"]
#[doc = "      ]"]
#[doc = "    },"]
#[doc = "    \"SubtitleStreamIndex\": {"]
#[doc = "      \"description\": \"Gets or sets the index of the now playing subtitle stream.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"VolumeLevel\": {"]
#[doc = "      \"description\": \"Gets or sets the volume level.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    }"]
#[doc = "  }"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug, Default)]
pub struct PlayerStateInfo {
    #[doc = "Gets or sets the index of the now playing audio stream."]
    #[serde(
        rename = "AudioStreamIndex",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub audio_stream_index: ::std::option::Option<i32>,
    #[doc = "Gets or sets a value indicating whether this instance can seek."]
    #[serde(
        rename = "CanSeek",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub can_seek: ::std::option::Option<bool>,
    #[doc = "Gets or sets a value indicating whether this instance is muted."]
    #[serde(
        rename = "IsMuted",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub is_muted: ::std::option::Option<bool>,
    #[doc = "Gets or sets a value indicating whether this instance is paused."]
    #[serde(
        rename = "IsPaused",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub is_paused: ::std::option::Option<bool>,
    #[doc = "Gets or sets the now playing live stream identifier."]
    #[serde(
        rename = "LiveStreamId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub live_stream_id: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the now playing media version identifier."]
    #[serde(
        rename = "MediaSourceId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub media_source_id: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "PlayMethod",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub play_method: ::std::option::Option<PlayMethod>,
    #[serde(
        rename = "PlaybackOrder",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub playback_order: ::std::option::Option<PlaybackOrder>,
    #[doc = "Gets or sets the now playing position ticks."]
    #[serde(
        rename = "PositionTicks",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub position_ticks: ::std::option::Option<i64>,
    #[serde(
        rename = "RepeatMode",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub repeat_mode: ::std::option::Option<RepeatMode>,
    #[doc = "Gets or sets the index of the now playing subtitle stream."]
    #[serde(
        rename = "SubtitleStreamIndex",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub subtitle_stream_index: ::std::option::Option<i32>,
    #[doc = "Gets or sets the volume level."]
    #[serde(
        rename = "VolumeLevel",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub volume_level: ::std::option::Option<i32>,
}
#[doc = "`ProfileCondition`"]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"properties\": {"]
#[doc = "    \"Condition\": {"]
#[doc = "      \"enum\": ["]
#[doc = "        \"Equals\","]
#[doc = "        \"NotEquals\","]
#[doc = "        \"LessThanEqual\","]
#[doc = "        \"GreaterThanEqual\","]
#[doc = "        \"EqualsAny\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/ProfileConditionType\""]
#[doc = "        }"]
#[doc = "      ]"]
#[doc = "    },"]
#[doc = "    \"IsRequired\": {"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"Property\": {"]
#[doc = "      \"enum\": ["]
#[doc = "        \"AudioChannels\","]
#[doc = "        \"AudioBitrate\","]
#[doc = "        \"AudioProfile\","]
#[doc = "        \"Width\","]
#[doc = "        \"Height\","]
#[doc = "        \"Has64BitOffsets\","]
#[doc = "        \"PacketLength\","]
#[doc = "        \"VideoBitDepth\","]
#[doc = "        \"VideoBitrate\","]
#[doc = "        \"VideoFramerate\","]
#[doc = "        \"VideoLevel\","]
#[doc = "        \"VideoProfile\","]
#[doc = "        \"VideoTimestamp\","]
#[doc = "        \"IsAnamorphic\","]
#[doc = "        \"RefFrames\","]
#[doc = "        \"NumAudioStreams\","]
#[doc = "        \"NumVideoStreams\","]
#[doc = "        \"IsSecondaryAudio\","]
#[doc = "        \"VideoCodecTag\","]
#[doc = "        \"IsAvc\","]
#[doc = "        \"IsInterlaced\","]
#[doc = "        \"AudioSampleRate\","]
#[doc = "        \"AudioBitDepth\","]
#[doc = "        \"VideoRangeType\","]
#[doc = "        \"NumStreams\","]
#[doc = "        \"VideoRotation\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/ProfileConditionValue\""]
#[doc = "        }"]
#[doc = "      ]"]
#[doc = "    },"]
#[doc = "    \"Value\": {"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    }"]
#[doc = "  }"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug, Default)]
pub struct ProfileCondition {
    #[serde(
        rename = "Condition",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub condition: ::std::option::Option<ProfileConditionType>,
    #[serde(
        rename = "IsRequired",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub is_required: ::std::option::Option<bool>,
    #[serde(
        rename = "Property",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub property: ::std::option::Option<ProfileConditionValue>,
    #[serde(
        rename = "Value",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub value: ::std::option::Option<::std::string::String>,
}
#[doc = "`ProfileConditionType`"]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"Equals\","]
#[doc = "    \"NotEquals\","]
#[doc = "    \"LessThanEqual\","]
#[doc = "    \"GreaterThanEqual\","]
#[doc = "    \"EqualsAny\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum ProfileConditionType {
    Equals,
    NotEquals,
    LessThanEqual,
    GreaterThanEqual,
    EqualsAny,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for ProfileConditionType {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::Equals => f.write_str("Equals"),
            Self::NotEquals => f.write_str("NotEquals"),
            Self::LessThanEqual => f.write_str("LessThanEqual"),
            Self::GreaterThanEqual => f.write_str("GreaterThanEqual"),
            Self::EqualsAny => f.write_str("EqualsAny"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for ProfileConditionType {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "Equals" => Ok(Self::Equals),
            "NotEquals" => Ok(Self::NotEquals),
            "LessThanEqual" => Ok(Self::LessThanEqual),
            "GreaterThanEqual" => Ok(Self::GreaterThanEqual),
            "EqualsAny" => Ok(Self::EqualsAny),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for ProfileConditionType {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for ProfileConditionType {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for ProfileConditionType {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "`ProfileConditionValue`"]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"AudioChannels\","]
#[doc = "    \"AudioBitrate\","]
#[doc = "    \"AudioProfile\","]
#[doc = "    \"Width\","]
#[doc = "    \"Height\","]
#[doc = "    \"Has64BitOffsets\","]
#[doc = "    \"PacketLength\","]
#[doc = "    \"VideoBitDepth\","]
#[doc = "    \"VideoBitrate\","]
#[doc = "    \"VideoFramerate\","]
#[doc = "    \"VideoLevel\","]
#[doc = "    \"VideoProfile\","]
#[doc = "    \"VideoTimestamp\","]
#[doc = "    \"IsAnamorphic\","]
#[doc = "    \"RefFrames\","]
#[doc = "    \"NumAudioStreams\","]
#[doc = "    \"NumVideoStreams\","]
#[doc = "    \"IsSecondaryAudio\","]
#[doc = "    \"VideoCodecTag\","]
#[doc = "    \"IsAvc\","]
#[doc = "    \"IsInterlaced\","]
#[doc = "    \"AudioSampleRate\","]
#[doc = "    \"AudioBitDepth\","]
#[doc = "    \"VideoRangeType\","]
#[doc = "    \"NumStreams\","]
#[doc = "    \"VideoRotation\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum ProfileConditionValue {
    AudioChannels,
    AudioBitrate,
    AudioProfile,
    Width,
    Height,
    Has64BitOffsets,
    PacketLength,
    VideoBitDepth,
    VideoBitrate,
    VideoFramerate,
    VideoLevel,
    VideoProfile,
    VideoTimestamp,
    IsAnamorphic,
    RefFrames,
    NumAudioStreams,
    NumVideoStreams,
    IsSecondaryAudio,
    VideoCodecTag,
    IsAvc,
    IsInterlaced,
    AudioSampleRate,
    AudioBitDepth,
    VideoRangeType,
    NumStreams,
    VideoRotation,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for ProfileConditionValue {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::AudioChannels => f.write_str("AudioChannels"),
            Self::AudioBitrate => f.write_str("AudioBitrate"),
            Self::AudioProfile => f.write_str("AudioProfile"),
            Self::Width => f.write_str("Width"),
            Self::Height => f.write_str("Height"),
            Self::Has64BitOffsets => f.write_str("Has64BitOffsets"),
            Self::PacketLength => f.write_str("PacketLength"),
            Self::VideoBitDepth => f.write_str("VideoBitDepth"),
            Self::VideoBitrate => f.write_str("VideoBitrate"),
            Self::VideoFramerate => f.write_str("VideoFramerate"),
            Self::VideoLevel => f.write_str("VideoLevel"),
            Self::VideoProfile => f.write_str("VideoProfile"),
            Self::VideoTimestamp => f.write_str("VideoTimestamp"),
            Self::IsAnamorphic => f.write_str("IsAnamorphic"),
            Self::RefFrames => f.write_str("RefFrames"),
            Self::NumAudioStreams => f.write_str("NumAudioStreams"),
            Self::NumVideoStreams => f.write_str("NumVideoStreams"),
            Self::IsSecondaryAudio => f.write_str("IsSecondaryAudio"),
            Self::VideoCodecTag => f.write_str("VideoCodecTag"),
            Self::IsAvc => f.write_str("IsAvc"),
            Self::IsInterlaced => f.write_str("IsInterlaced"),
            Self::AudioSampleRate => f.write_str("AudioSampleRate"),
            Self::AudioBitDepth => f.write_str("AudioBitDepth"),
            Self::VideoRangeType => f.write_str("VideoRangeType"),
            Self::NumStreams => f.write_str("NumStreams"),
            Self::VideoRotation => f.write_str("VideoRotation"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for ProfileConditionValue {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "AudioChannels" => Ok(Self::AudioChannels),
            "AudioBitrate" => Ok(Self::AudioBitrate),
            "AudioProfile" => Ok(Self::AudioProfile),
            "Width" => Ok(Self::Width),
            "Height" => Ok(Self::Height),
            "Has64BitOffsets" => Ok(Self::Has64BitOffsets),
            "PacketLength" => Ok(Self::PacketLength),
            "VideoBitDepth" => Ok(Self::VideoBitDepth),
            "VideoBitrate" => Ok(Self::VideoBitrate),
            "VideoFramerate" => Ok(Self::VideoFramerate),
            "VideoLevel" => Ok(Self::VideoLevel),
            "VideoProfile" => Ok(Self::VideoProfile),
            "VideoTimestamp" => Ok(Self::VideoTimestamp),
            "IsAnamorphic" => Ok(Self::IsAnamorphic),
            "RefFrames" => Ok(Self::RefFrames),
            "NumAudioStreams" => Ok(Self::NumAudioStreams),
            "NumVideoStreams" => Ok(Self::NumVideoStreams),
            "IsSecondaryAudio" => Ok(Self::IsSecondaryAudio),
            "VideoCodecTag" => Ok(Self::VideoCodecTag),
            "IsAvc" => Ok(Self::IsAvc),
            "IsInterlaced" => Ok(Self::IsInterlaced),
            "AudioSampleRate" => Ok(Self::AudioSampleRate),
            "AudioBitDepth" => Ok(Self::AudioBitDepth),
            "VideoRangeType" => Ok(Self::VideoRangeType),
            "NumStreams" => Ok(Self::NumStreams),
            "VideoRotation" => Ok(Self::VideoRotation),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for ProfileConditionValue {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for ProfileConditionValue {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for ProfileConditionValue {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "`ProgramAudio`"]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"Mono\","]
#[doc = "    \"Stereo\","]
#[doc = "    \"Dolby\","]
#[doc = "    \"DolbyDigital\","]
#[doc = "    \"Thx\","]
#[doc = "    \"Atmos\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum ProgramAudio {
    Mono,
    Stereo,
    Dolby,
    DolbyDigital,
    Thx,
    Atmos,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for ProgramAudio {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::Mono => f.write_str("Mono"),
            Self::Stereo => f.write_str("Stereo"),
            Self::Dolby => f.write_str("Dolby"),
            Self::DolbyDigital => f.write_str("DolbyDigital"),
            Self::Thx => f.write_str("Thx"),
            Self::Atmos => f.write_str("Atmos"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for ProgramAudio {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "Mono" => Ok(Self::Mono),
            "Stereo" => Ok(Self::Stereo),
            "Dolby" => Ok(Self::Dolby),
            "DolbyDigital" => Ok(Self::DolbyDigital),
            "Thx" => Ok(Self::Thx),
            "Atmos" => Ok(Self::Atmos),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for ProgramAudio {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for ProgramAudio {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for ProgramAudio {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "An item in a play queue."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"An item in a play queue.\","]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"properties\": {"]
#[doc = "    \"Id\": {"]
#[doc = "      \"description\": \"Gets or sets the item id.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"format\": \"uuid\""]
#[doc = "    },"]
#[doc = "    \"PlaylistItemId\": {"]
#[doc = "      \"description\": \"Gets or sets the playlist item id.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    }"]
#[doc = "  }"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug, Default)]
pub struct QueueItem {
    #[doc = "Gets or sets the item id."]
    #[serde(
        rename = "Id",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub id: ::std::option::Option<::uuid::Uuid>,
    #[doc = "Gets or sets the playlist item id."]
    #[serde(
        rename = "PlaylistItemId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub playlist_item_id: ::std::option::Option<::std::string::String>,
}
#[doc = "The quick connect request body."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"The quick connect request body.\","]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"required\": ["]
#[doc = "    \"Secret\""]
#[doc = "  ],"]
#[doc = "  \"properties\": {"]
#[doc = "    \"Secret\": {"]
#[doc = "      \"description\": \"Gets or sets the quick connect secret.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"minLength\": 1"]
#[doc = "    }"]
#[doc = "  }"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug)]
pub struct QuickConnectDto {
    #[doc = "Gets or sets the quick connect secret."]
    #[serde(rename = "Secret")]
    pub secret: QuickConnectDtoSecret,
}
#[doc = "Gets or sets the quick connect secret."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"Gets or sets the quick connect secret.\","]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"minLength\": 1"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Serialize, Clone, Debug, Eq, Hash, Ord, PartialEq, PartialOrd)]
#[serde(transparent)]
pub struct QuickConnectDtoSecret(::std::string::String);
impl ::std::ops::Deref for QuickConnectDtoSecret {
    type Target = ::std::string::String;
    fn deref(&self) -> &::std::string::String {
        &self.0
    }
}
impl ::std::convert::From<QuickConnectDtoSecret> for ::std::string::String {
    fn from(value: QuickConnectDtoSecret) -> Self {
        value.0
    }
}
impl ::std::str::FromStr for QuickConnectDtoSecret {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        if value.chars().count() < 1usize {
            return Err("shorter than 1 characters".into());
        }
        Ok(Self(value.to_string()))
    }
}
impl ::std::convert::TryFrom<&str> for QuickConnectDtoSecret {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for QuickConnectDtoSecret {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for QuickConnectDtoSecret {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl<'de> ::serde::Deserialize<'de> for QuickConnectDtoSecret {
    fn deserialize<D>(deserializer: D) -> ::std::result::Result<Self, D::Error>
    where
        D: ::serde::Deserializer<'de>,
    {
        ::std::string::String::deserialize(deserializer)?
            .parse()
            .map_err(|e: self::error::ConversionError| {
                <D::Error as ::serde::de::Error>::custom(e.to_string())
            })
    }
}
#[doc = "Stores the state of an quick connect request."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"Stores the state of an quick connect request.\","]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"properties\": {"]
#[doc = "    \"AppName\": {"]
#[doc = "      \"description\": \"Gets the requesting app name.\","]
#[doc = "      \"type\": \"string\""]
#[doc = "    },"]
#[doc = "    \"AppVersion\": {"]
#[doc = "      \"description\": \"Gets the requesting app version.\","]
#[doc = "      \"type\": \"string\""]
#[doc = "    },"]
#[doc = "    \"Authenticated\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether this request is authorized.\","]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"Code\": {"]
#[doc = "      \"description\": \"Gets the user facing code used so the user can quickly differentiate this request from others.\","]
#[doc = "      \"type\": \"string\""]
#[doc = "    },"]
#[doc = "    \"DateAdded\": {"]
#[doc = "      \"description\": \"Gets or sets the DateTime that this request was created.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"format\": \"date-time\""]
#[doc = "    },"]
#[doc = "    \"DeviceId\": {"]
#[doc = "      \"description\": \"Gets the requesting device id.\","]
#[doc = "      \"type\": \"string\""]
#[doc = "    },"]
#[doc = "    \"DeviceName\": {"]
#[doc = "      \"description\": \"Gets the requesting device name.\","]
#[doc = "      \"type\": \"string\""]
#[doc = "    },"]
#[doc = "    \"Secret\": {"]
#[doc = "      \"description\": \"Gets the secret value used to uniquely identify this request. Can be used to retrieve authentication information.\","]
#[doc = "      \"type\": \"string\""]
#[doc = "    }"]
#[doc = "  }"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug, Default)]
pub struct QuickConnectResult {
    #[doc = "Gets the requesting app name."]
    #[serde(
        rename = "AppName",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub app_name: ::std::option::Option<::std::string::String>,
    #[doc = "Gets the requesting app version."]
    #[serde(
        rename = "AppVersion",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub app_version: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets a value indicating whether this request is authorized."]
    #[serde(
        rename = "Authenticated",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub authenticated: ::std::option::Option<bool>,
    #[doc = "Gets the user facing code used so the user can quickly differentiate this request from others."]
    #[serde(
        rename = "Code",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub code: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the DateTime that this request was created."]
    #[serde(
        rename = "DateAdded",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub date_added: ::std::option::Option<::chrono::DateTime<::chrono::offset::Utc>>,
    #[doc = "Gets the requesting device id."]
    #[serde(
        rename = "DeviceId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub device_id: ::std::option::Option<::std::string::String>,
    #[doc = "Gets the requesting device name."]
    #[serde(
        rename = "DeviceName",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub device_name: ::std::option::Option<::std::string::String>,
    #[doc = "Gets the secret value used to uniquely identify this request. Can be used to retrieve authentication information."]
    #[serde(
        rename = "Secret",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub secret: ::std::option::Option<::std::string::String>,
}
#[doc = "The repeat mode of a play queue."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"The repeat mode of a play queue.\","]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"RepeatNone\","]
#[doc = "    \"RepeatAll\","]
#[doc = "    \"RepeatOne\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum RepeatMode {
    RepeatNone,
    RepeatAll,
    RepeatOne,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for RepeatMode {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::RepeatNone => f.write_str("RepeatNone"),
            Self::RepeatAll => f.write_str("RepeatAll"),
            Self::RepeatOne => f.write_str("RepeatOne"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for RepeatMode {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "RepeatNone" => Ok(Self::RepeatNone),
            "RepeatAll" => Ok(Self::RepeatAll),
            "RepeatOne" => Ok(Self::RepeatOne),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for RepeatMode {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for RepeatMode {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for RepeatMode {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "Session info DTO."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"Session info DTO.\","]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"properties\": {"]
#[doc = "    \"AdditionalUsers\": {"]
#[doc = "      \"description\": \"Gets or sets the additional users.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"$ref\": \"#/$defs/SessionUserInfo\""]
#[doc = "      },"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ApplicationVersion\": {"]
#[doc = "      \"description\": \"Gets or sets the application version.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Capabilities\": {"]
#[doc = "      \"description\": \"Gets or sets the client capabilities.\","]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/ClientCapabilitiesDto\""]
#[doc = "        }"]
#[doc = "      ],"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Client\": {"]
#[doc = "      \"description\": \"Gets or sets the type of the client.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"DeviceId\": {"]
#[doc = "      \"description\": \"Gets or sets the device id.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"DeviceName\": {"]
#[doc = "      \"description\": \"Gets or sets the name of the device.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"DeviceType\": {"]
#[doc = "      \"description\": \"Gets or sets the type of the device.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"HasCustomDeviceName\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether this session has a custom device name.\","]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"Id\": {"]
#[doc = "      \"description\": \"Gets or sets the id.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"IsActive\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether this session is active.\","]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"LastActivityDate\": {"]
#[doc = "      \"description\": \"Gets or sets the last activity date.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"format\": \"date-time\""]
#[doc = "    },"]
#[doc = "    \"LastPausedDate\": {"]
#[doc = "      \"description\": \"Gets or sets the last paused date.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"format\": \"date-time\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"LastPlaybackCheckIn\": {"]
#[doc = "      \"description\": \"Gets or sets the last playback check in.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"format\": \"date-time\""]
#[doc = "    },"]
#[doc = "    \"NowPlayingItem\": {"]
#[doc = "      \"description\": \"Gets or sets the now playing item.\","]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/BaseItemDto\""]
#[doc = "        }"]
#[doc = "      ],"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"NowPlayingQueue\": {"]
#[doc = "      \"description\": \"Gets or sets the now playing queue.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"$ref\": \"#/$defs/QueueItem\""]
#[doc = "      },"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"NowViewingItem\": {"]
#[doc = "      \"description\": \"Gets or sets the now viewing item.\","]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/BaseItemDto\""]
#[doc = "        }"]
#[doc = "      ],"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"PlayState\": {"]
#[doc = "      \"description\": \"Gets or sets the play state.\","]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/PlayerStateInfo\""]
#[doc = "        }"]
#[doc = "      ],"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"PlayableMediaTypes\": {"]
#[doc = "      \"description\": \"Gets or sets the playable media types.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"$ref\": \"#/$defs/MediaType\""]
#[doc = "      }"]
#[doc = "    },"]
#[doc = "    \"PlaylistItemId\": {"]
#[doc = "      \"description\": \"Gets or sets the playlist item id.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"RemoteEndPoint\": {"]
#[doc = "      \"description\": \"Gets or sets the remote end point.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ServerId\": {"]
#[doc = "      \"description\": \"Gets or sets the server id.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"SupportedCommands\": {"]
#[doc = "      \"description\": \"Gets or sets the supported commands.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"$ref\": \"#/$defs/GeneralCommandType\""]
#[doc = "      }"]
#[doc = "    },"]
#[doc = "    \"SupportsMediaControl\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether the session supports media control.\","]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"SupportsRemoteControl\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether the session supports remote control.\","]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"TranscodingInfo\": {"]
#[doc = "      \"description\": \"Gets or sets the transcoding info.\","]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/TranscodingInfo\""]
#[doc = "        }"]
#[doc = "      ],"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"UserId\": {"]
#[doc = "      \"description\": \"Gets or sets the user id.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"format\": \"uuid\""]
#[doc = "    },"]
#[doc = "    \"UserName\": {"]
#[doc = "      \"description\": \"Gets or sets the username.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"UserPrimaryImageTag\": {"]
#[doc = "      \"description\": \"Gets or sets the user primary image tag.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    }"]
#[doc = "  }"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug, Default)]
pub struct SessionInfoDto {
    #[doc = "Gets or sets the additional users."]
    #[serde(
        rename = "AdditionalUsers",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub additional_users: ::std::vec::Vec<SessionUserInfo>,
    #[doc = "Gets or sets the application version."]
    #[serde(
        rename = "ApplicationVersion",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub application_version: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the client capabilities."]
    #[serde(
        rename = "Capabilities",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub capabilities: ::std::option::Option<ClientCapabilitiesDto>,
    #[doc = "Gets or sets the type of the client."]
    #[serde(
        rename = "Client",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub client: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the device id."]
    #[serde(
        rename = "DeviceId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub device_id: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the name of the device."]
    #[serde(
        rename = "DeviceName",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub device_name: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the type of the device."]
    #[serde(
        rename = "DeviceType",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub device_type: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets a value indicating whether this session has a custom device name."]
    #[serde(
        rename = "HasCustomDeviceName",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub has_custom_device_name: ::std::option::Option<bool>,
    #[doc = "Gets or sets the id."]
    #[serde(
        rename = "Id",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub id: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets a value indicating whether this session is active."]
    #[serde(
        rename = "IsActive",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub is_active: ::std::option::Option<bool>,
    #[doc = "Gets or sets the last activity date."]
    #[serde(
        rename = "LastActivityDate",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub last_activity_date: ::std::option::Option<::chrono::DateTime<::chrono::offset::Utc>>,
    #[doc = "Gets or sets the last paused date."]
    #[serde(
        rename = "LastPausedDate",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub last_paused_date: ::std::option::Option<::chrono::DateTime<::chrono::offset::Utc>>,
    #[doc = "Gets or sets the last playback check in."]
    #[serde(
        rename = "LastPlaybackCheckIn",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub last_playback_check_in: ::std::option::Option<::chrono::DateTime<::chrono::offset::Utc>>,
    #[doc = "Gets or sets the now playing item."]
    #[serde(
        rename = "NowPlayingItem",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub now_playing_item: ::std::option::Option<BaseItemDto>,
    #[doc = "Gets or sets the now playing queue."]
    #[serde(
        rename = "NowPlayingQueue",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub now_playing_queue: ::std::vec::Vec<QueueItem>,
    #[doc = "Gets or sets the now viewing item."]
    #[serde(
        rename = "NowViewingItem",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub now_viewing_item: ::std::option::Option<BaseItemDto>,
    #[doc = "Gets or sets the play state."]
    #[serde(
        rename = "PlayState",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub play_state: ::std::option::Option<PlayerStateInfo>,
    #[doc = "Gets or sets the playable media types."]
    #[serde(
        rename = "PlayableMediaTypes",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub playable_media_types: ::std::vec::Vec<MediaType>,
    #[doc = "Gets or sets the playlist item id."]
    #[serde(
        rename = "PlaylistItemId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub playlist_item_id: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the remote end point."]
    #[serde(
        rename = "RemoteEndPoint",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub remote_end_point: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the server id."]
    #[serde(
        rename = "ServerId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub server_id: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the supported commands."]
    #[serde(
        rename = "SupportedCommands",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub supported_commands: ::std::vec::Vec<GeneralCommandType>,
    #[doc = "Gets or sets a value indicating whether the session supports media control."]
    #[serde(
        rename = "SupportsMediaControl",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub supports_media_control: ::std::option::Option<bool>,
    #[doc = "Gets or sets a value indicating whether the session supports remote control."]
    #[serde(
        rename = "SupportsRemoteControl",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub supports_remote_control: ::std::option::Option<bool>,
    #[doc = "Gets or sets the transcoding info."]
    #[serde(
        rename = "TranscodingInfo",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub transcoding_info: ::std::option::Option<TranscodingInfo>,
    #[doc = "Gets or sets the user id."]
    #[serde(
        rename = "UserId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub user_id: ::std::option::Option<::uuid::Uuid>,
    #[doc = "Gets or sets the username."]
    #[serde(
        rename = "UserName",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub user_name: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the user primary image tag."]
    #[serde(
        rename = "UserPrimaryImageTag",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub user_primary_image_tag: ::std::option::Option<::std::string::String>,
}
#[doc = "Class SessionUserInfo."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"Class SessionUserInfo.\","]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"properties\": {"]
#[doc = "    \"UserId\": {"]
#[doc = "      \"description\": \"Gets or sets the user identifier.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"format\": \"uuid\""]
#[doc = "    },"]
#[doc = "    \"UserName\": {"]
#[doc = "      \"description\": \"Gets or sets the name of the user.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    }"]
#[doc = "  }"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug, Default)]
pub struct SessionUserInfo {
    #[doc = "Gets or sets the user identifier."]
    #[serde(
        rename = "UserId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub user_id: ::std::option::Option<::uuid::Uuid>,
    #[doc = "Gets or sets the name of the user."]
    #[serde(
        rename = "UserName",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub user_name: ::std::option::Option<::std::string::String>,
}
#[doc = "Delivery method to use during playback of a specific subtitle format."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"Delivery method to use during playback of a specific subtitle format.\","]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"Encode\","]
#[doc = "    \"Embed\","]
#[doc = "    \"External\","]
#[doc = "    \"Hls\","]
#[doc = "    \"Drop\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum SubtitleDeliveryMethod {
    Encode,
    Embed,
    External,
    Hls,
    Drop,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for SubtitleDeliveryMethod {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::Encode => f.write_str("Encode"),
            Self::Embed => f.write_str("Embed"),
            Self::External => f.write_str("External"),
            Self::Hls => f.write_str("Hls"),
            Self::Drop => f.write_str("Drop"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for SubtitleDeliveryMethod {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "Encode" => Ok(Self::Encode),
            "Embed" => Ok(Self::Embed),
            "External" => Ok(Self::External),
            "Hls" => Ok(Self::Hls),
            "Drop" => Ok(Self::Drop),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for SubtitleDeliveryMethod {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for SubtitleDeliveryMethod {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for SubtitleDeliveryMethod {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "An enum representing a subtitle playback mode."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"An enum representing a subtitle playback mode.\","]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"Default\","]
#[doc = "    \"Always\","]
#[doc = "    \"OnlyForced\","]
#[doc = "    \"None\","]
#[doc = "    \"Smart\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum SubtitlePlaybackMode {
    Default,
    Always,
    OnlyForced,
    None,
    Smart,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for SubtitlePlaybackMode {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::Default => f.write_str("Default"),
            Self::Always => f.write_str("Always"),
            Self::OnlyForced => f.write_str("OnlyForced"),
            Self::None => f.write_str("None"),
            Self::Smart => f.write_str("Smart"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for SubtitlePlaybackMode {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "Default" => Ok(Self::Default),
            "Always" => Ok(Self::Always),
            "OnlyForced" => Ok(Self::OnlyForced),
            "None" => Ok(Self::None),
            "Smart" => Ok(Self::Smart),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for SubtitlePlaybackMode {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for SubtitlePlaybackMode {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for SubtitlePlaybackMode {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "A class for subtitle profile information."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"A class for subtitle profile information.\","]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"properties\": {"]
#[doc = "    \"Container\": {"]
#[doc = "      \"description\": \"Gets or sets the container.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"DidlMode\": {"]
#[doc = "      \"description\": \"Gets or sets the DIDL mode.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Format\": {"]
#[doc = "      \"description\": \"Gets or sets the format.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Language\": {"]
#[doc = "      \"description\": \"Gets or sets the language.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Method\": {"]
#[doc = "      \"description\": \"Gets or sets the delivery method.\","]
#[doc = "      \"enum\": ["]
#[doc = "        \"Encode\","]
#[doc = "        \"Embed\","]
#[doc = "        \"External\","]
#[doc = "        \"Hls\","]
#[doc = "        \"Drop\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/SubtitleDeliveryMethod\""]
#[doc = "        }"]
#[doc = "      ]"]
#[doc = "    }"]
#[doc = "  }"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug, Default)]
pub struct SubtitleProfile {
    #[doc = "Gets or sets the container."]
    #[serde(
        rename = "Container",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub container: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the DIDL mode."]
    #[serde(
        rename = "DidlMode",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub didl_mode: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the format."]
    #[serde(
        rename = "Format",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub format: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the language."]
    #[serde(
        rename = "Language",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub language: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "Method",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub method: ::std::option::Option<SubtitleDeliveryMethod>,
}
#[doc = "Enum SyncPlayUserAccessType."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"Enum SyncPlayUserAccessType.\","]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"CreateAndJoinGroups\","]
#[doc = "    \"JoinGroups\","]
#[doc = "    \"None\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum SyncPlayUserAccessType {
    CreateAndJoinGroups,
    JoinGroups,
    None,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for SyncPlayUserAccessType {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::CreateAndJoinGroups => f.write_str("CreateAndJoinGroups"),
            Self::JoinGroups => f.write_str("JoinGroups"),
            Self::None => f.write_str("None"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for SyncPlayUserAccessType {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "CreateAndJoinGroups" => Ok(Self::CreateAndJoinGroups),
            "JoinGroups" => Ok(Self::JoinGroups),
            "None" => Ok(Self::None),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for SyncPlayUserAccessType {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for SyncPlayUserAccessType {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for SyncPlayUserAccessType {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "`TranscodeReason`"]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"ContainerNotSupported\","]
#[doc = "    \"VideoCodecNotSupported\","]
#[doc = "    \"AudioCodecNotSupported\","]
#[doc = "    \"SubtitleCodecNotSupported\","]
#[doc = "    \"AudioIsExternal\","]
#[doc = "    \"SecondaryAudioNotSupported\","]
#[doc = "    \"VideoProfileNotSupported\","]
#[doc = "    \"VideoLevelNotSupported\","]
#[doc = "    \"VideoResolutionNotSupported\","]
#[doc = "    \"VideoBitDepthNotSupported\","]
#[doc = "    \"VideoFramerateNotSupported\","]
#[doc = "    \"RefFramesNotSupported\","]
#[doc = "    \"AnamorphicVideoNotSupported\","]
#[doc = "    \"InterlacedVideoNotSupported\","]
#[doc = "    \"AudioChannelsNotSupported\","]
#[doc = "    \"AudioProfileNotSupported\","]
#[doc = "    \"AudioSampleRateNotSupported\","]
#[doc = "    \"AudioBitDepthNotSupported\","]
#[doc = "    \"ContainerBitrateExceedsLimit\","]
#[doc = "    \"VideoBitrateNotSupported\","]
#[doc = "    \"AudioBitrateNotSupported\","]
#[doc = "    \"UnknownVideoStreamInfo\","]
#[doc = "    \"UnknownAudioStreamInfo\","]
#[doc = "    \"DirectPlayError\","]
#[doc = "    \"VideoRangeTypeNotSupported\","]
#[doc = "    \"VideoCodecTagNotSupported\","]
#[doc = "    \"StreamCountExceedsLimit\","]
#[doc = "    \"VideoRotationNotSupported\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum TranscodeReason {
    ContainerNotSupported,
    VideoCodecNotSupported,
    AudioCodecNotSupported,
    SubtitleCodecNotSupported,
    AudioIsExternal,
    SecondaryAudioNotSupported,
    VideoProfileNotSupported,
    VideoLevelNotSupported,
    VideoResolutionNotSupported,
    VideoBitDepthNotSupported,
    VideoFramerateNotSupported,
    RefFramesNotSupported,
    AnamorphicVideoNotSupported,
    InterlacedVideoNotSupported,
    AudioChannelsNotSupported,
    AudioProfileNotSupported,
    AudioSampleRateNotSupported,
    AudioBitDepthNotSupported,
    ContainerBitrateExceedsLimit,
    VideoBitrateNotSupported,
    AudioBitrateNotSupported,
    UnknownVideoStreamInfo,
    UnknownAudioStreamInfo,
    DirectPlayError,
    VideoRangeTypeNotSupported,
    VideoCodecTagNotSupported,
    StreamCountExceedsLimit,
    VideoRotationNotSupported,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for TranscodeReason {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::ContainerNotSupported => f.write_str("ContainerNotSupported"),
            Self::VideoCodecNotSupported => f.write_str("VideoCodecNotSupported"),
            Self::AudioCodecNotSupported => f.write_str("AudioCodecNotSupported"),
            Self::SubtitleCodecNotSupported => f.write_str("SubtitleCodecNotSupported"),
            Self::AudioIsExternal => f.write_str("AudioIsExternal"),
            Self::SecondaryAudioNotSupported => f.write_str("SecondaryAudioNotSupported"),
            Self::VideoProfileNotSupported => f.write_str("VideoProfileNotSupported"),
            Self::VideoLevelNotSupported => f.write_str("VideoLevelNotSupported"),
            Self::VideoResolutionNotSupported => f.write_str("VideoResolutionNotSupported"),
            Self::VideoBitDepthNotSupported => f.write_str("VideoBitDepthNotSupported"),
            Self::VideoFramerateNotSupported => f.write_str("VideoFramerateNotSupported"),
            Self::RefFramesNotSupported => f.write_str("RefFramesNotSupported"),
            Self::AnamorphicVideoNotSupported => f.write_str("AnamorphicVideoNotSupported"),
            Self::InterlacedVideoNotSupported => f.write_str("InterlacedVideoNotSupported"),
            Self::AudioChannelsNotSupported => f.write_str("AudioChannelsNotSupported"),
            Self::AudioProfileNotSupported => f.write_str("AudioProfileNotSupported"),
            Self::AudioSampleRateNotSupported => f.write_str("AudioSampleRateNotSupported"),
            Self::AudioBitDepthNotSupported => f.write_str("AudioBitDepthNotSupported"),
            Self::ContainerBitrateExceedsLimit => f.write_str("ContainerBitrateExceedsLimit"),
            Self::VideoBitrateNotSupported => f.write_str("VideoBitrateNotSupported"),
            Self::AudioBitrateNotSupported => f.write_str("AudioBitrateNotSupported"),
            Self::UnknownVideoStreamInfo => f.write_str("UnknownVideoStreamInfo"),
            Self::UnknownAudioStreamInfo => f.write_str("UnknownAudioStreamInfo"),
            Self::DirectPlayError => f.write_str("DirectPlayError"),
            Self::VideoRangeTypeNotSupported => f.write_str("VideoRangeTypeNotSupported"),
            Self::VideoCodecTagNotSupported => f.write_str("VideoCodecTagNotSupported"),
            Self::StreamCountExceedsLimit => f.write_str("StreamCountExceedsLimit"),
            Self::VideoRotationNotSupported => f.write_str("VideoRotationNotSupported"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for TranscodeReason {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "ContainerNotSupported" => Ok(Self::ContainerNotSupported),
            "VideoCodecNotSupported" => Ok(Self::VideoCodecNotSupported),
            "AudioCodecNotSupported" => Ok(Self::AudioCodecNotSupported),
            "SubtitleCodecNotSupported" => Ok(Self::SubtitleCodecNotSupported),
            "AudioIsExternal" => Ok(Self::AudioIsExternal),
            "SecondaryAudioNotSupported" => Ok(Self::SecondaryAudioNotSupported),
            "VideoProfileNotSupported" => Ok(Self::VideoProfileNotSupported),
            "VideoLevelNotSupported" => Ok(Self::VideoLevelNotSupported),
            "VideoResolutionNotSupported" => Ok(Self::VideoResolutionNotSupported),
            "VideoBitDepthNotSupported" => Ok(Self::VideoBitDepthNotSupported),
            "VideoFramerateNotSupported" => Ok(Self::VideoFramerateNotSupported),
            "RefFramesNotSupported" => Ok(Self::RefFramesNotSupported),
            "AnamorphicVideoNotSupported" => Ok(Self::AnamorphicVideoNotSupported),
            "InterlacedVideoNotSupported" => Ok(Self::InterlacedVideoNotSupported),
            "AudioChannelsNotSupported" => Ok(Self::AudioChannelsNotSupported),
            "AudioProfileNotSupported" => Ok(Self::AudioProfileNotSupported),
            "AudioSampleRateNotSupported" => Ok(Self::AudioSampleRateNotSupported),
            "AudioBitDepthNotSupported" => Ok(Self::AudioBitDepthNotSupported),
            "ContainerBitrateExceedsLimit" => Ok(Self::ContainerBitrateExceedsLimit),
            "VideoBitrateNotSupported" => Ok(Self::VideoBitrateNotSupported),
            "AudioBitrateNotSupported" => Ok(Self::AudioBitrateNotSupported),
            "UnknownVideoStreamInfo" => Ok(Self::UnknownVideoStreamInfo),
            "UnknownAudioStreamInfo" => Ok(Self::UnknownAudioStreamInfo),
            "DirectPlayError" => Ok(Self::DirectPlayError),
            "VideoRangeTypeNotSupported" => Ok(Self::VideoRangeTypeNotSupported),
            "VideoCodecTagNotSupported" => Ok(Self::VideoCodecTagNotSupported),
            "StreamCountExceedsLimit" => Ok(Self::StreamCountExceedsLimit),
            "VideoRotationNotSupported" => Ok(Self::VideoRotationNotSupported),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for TranscodeReason {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for TranscodeReason {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for TranscodeReason {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "The transcode seek info."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"The transcode seek info.\","]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"Auto\","]
#[doc = "    \"Bytes\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum TranscodeSeekInfo {
    Auto,
    Bytes,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for TranscodeSeekInfo {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::Auto => f.write_str("Auto"),
            Self::Bytes => f.write_str("Bytes"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for TranscodeSeekInfo {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "Auto" => Ok(Self::Auto),
            "Bytes" => Ok(Self::Bytes),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for TranscodeSeekInfo {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for TranscodeSeekInfo {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for TranscodeSeekInfo {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "Class holding information on a running transcode."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"Class holding information on a running transcode.\","]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"properties\": {"]
#[doc = "    \"AudioChannels\": {"]
#[doc = "      \"description\": \"Gets or sets the audio channels.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"AudioCodec\": {"]
#[doc = "      \"description\": \"Gets or sets the thread count used for encoding.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Bitrate\": {"]
#[doc = "      \"description\": \"Gets or sets the bitrate.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"CompletionPercentage\": {"]
#[doc = "      \"description\": \"Gets or sets the completion percentage.\","]
#[doc = "      \"type\": \"number\","]
#[doc = "      \"format\": \"double\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Container\": {"]
#[doc = "      \"description\": \"Gets or sets the thread count used for encoding.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Framerate\": {"]
#[doc = "      \"description\": \"Gets or sets the framerate.\","]
#[doc = "      \"type\": \"number\","]
#[doc = "      \"format\": \"float\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"HardwareAccelerationType\": {"]
#[doc = "      \"description\": \"Gets or sets the hardware acceleration type.\","]
#[doc = "      \"enum\": ["]
#[doc = "        \"none\","]
#[doc = "        \"amf\","]
#[doc = "        \"qsv\","]
#[doc = "        \"nvenc\","]
#[doc = "        \"v4l2m2m\","]
#[doc = "        \"vaapi\","]
#[doc = "        \"videotoolbox\","]
#[doc = "        \"rkmpp\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/HardwareAccelerationType\""]
#[doc = "        }"]
#[doc = "      ],"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Height\": {"]
#[doc = "      \"description\": \"Gets or sets the video height.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"IsAudioDirect\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether the audio is passed through.\","]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"IsVideoDirect\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether the video is passed through.\","]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"TranscodeReasons\": {"]
#[doc = "      \"description\": \"Gets or sets the transcode reasons.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"$ref\": \"#/$defs/TranscodeReason\""]
#[doc = "      }"]
#[doc = "    },"]
#[doc = "    \"VideoCodec\": {"]
#[doc = "      \"description\": \"Gets or sets the thread count used for encoding.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Width\": {"]
#[doc = "      \"description\": \"Gets or sets the video width.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    }"]
#[doc = "  }"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug, Default)]
pub struct TranscodingInfo {
    #[doc = "Gets or sets the audio channels."]
    #[serde(
        rename = "AudioChannels",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub audio_channels: ::std::option::Option<i32>,
    #[doc = "Gets or sets the thread count used for encoding."]
    #[serde(
        rename = "AudioCodec",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub audio_codec: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the bitrate."]
    #[serde(
        rename = "Bitrate",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub bitrate: ::std::option::Option<i32>,
    #[doc = "Gets or sets the completion percentage."]
    #[serde(
        rename = "CompletionPercentage",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub completion_percentage: ::std::option::Option<f64>,
    #[doc = "Gets or sets the thread count used for encoding."]
    #[serde(
        rename = "Container",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub container: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the framerate."]
    #[serde(
        rename = "Framerate",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub framerate: ::std::option::Option<f32>,
    #[serde(
        rename = "HardwareAccelerationType",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub hardware_acceleration_type: ::std::option::Option<HardwareAccelerationType>,
    #[doc = "Gets or sets the video height."]
    #[serde(
        rename = "Height",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub height: ::std::option::Option<i32>,
    #[doc = "Gets or sets a value indicating whether the audio is passed through."]
    #[serde(
        rename = "IsAudioDirect",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub is_audio_direct: ::std::option::Option<bool>,
    #[doc = "Gets or sets a value indicating whether the video is passed through."]
    #[serde(
        rename = "IsVideoDirect",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub is_video_direct: ::std::option::Option<bool>,
    #[doc = "Gets or sets the transcode reasons."]
    #[serde(
        rename = "TranscodeReasons",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub transcode_reasons: ::std::vec::Vec<TranscodeReason>,
    #[doc = "Gets or sets the thread count used for encoding."]
    #[serde(
        rename = "VideoCodec",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub video_codec: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the video width."]
    #[serde(
        rename = "Width",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub width: ::std::option::Option<i32>,
}
#[doc = "A class for transcoding profile information.\nNote for client developers: Conditions defined in MediaBrowser.Model.Dlna.CodecProfile has higher priority and can override values defined here."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"A class for transcoding profile information.\\nNote for client developers: Conditions defined in MediaBrowser.Model.Dlna.CodecProfile has higher priority and can override values defined here.\","]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"properties\": {"]
#[doc = "    \"AudioCodec\": {"]
#[doc = "      \"description\": \"Gets or sets the audio codec.\","]
#[doc = "      \"type\": \"string\""]
#[doc = "    },"]
#[doc = "    \"BreakOnNonKeyFrames\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether breaking the video stream on non-keyframes is supported.\","]
#[doc = "      \"default\": false,"]
#[doc = "      \"deprecated\": true,"]
#[doc = "      \"type\": \"boolean\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Conditions\": {"]
#[doc = "      \"description\": \"Gets or sets the profile conditions.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"$ref\": \"#/$defs/ProfileCondition\""]
#[doc = "      }"]
#[doc = "    },"]
#[doc = "    \"Container\": {"]
#[doc = "      \"description\": \"Gets or sets the container.\","]
#[doc = "      \"type\": \"string\""]
#[doc = "    },"]
#[doc = "    \"Context\": {"]
#[doc = "      \"description\": \"Gets or sets the encoding context.\","]
#[doc = "      \"default\": \"Streaming\","]
#[doc = "      \"enum\": ["]
#[doc = "        \"Streaming\","]
#[doc = "        \"Static\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/EncodingContext\""]
#[doc = "        }"]
#[doc = "      ]"]
#[doc = "    },"]
#[doc = "    \"CopyTimestamps\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether timestamps should be copied.\","]
#[doc = "      \"default\": false,"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"EnableAudioVbrEncoding\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether variable bitrate encoding is supported.\","]
#[doc = "      \"default\": true,"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"EnableMpegtsM2TsMode\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether M2TS mode is enabled.\","]
#[doc = "      \"default\": false,"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"EnableSubtitlesInManifest\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether subtitles are allowed in the manifest.\","]
#[doc = "      \"default\": false,"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"EstimateContentLength\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether the content length should be estimated.\","]
#[doc = "      \"default\": false,"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"MaxAudioChannels\": {"]
#[doc = "      \"description\": \"Gets or sets the maximum audio channels.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"MinSegments\": {"]
#[doc = "      \"description\": \"Gets or sets the minimum amount of segments.\","]
#[doc = "      \"default\": 0,"]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\""]
#[doc = "    },"]
#[doc = "    \"Protocol\": {"]
#[doc = "      \"description\": \"Gets or sets the protocol.\","]
#[doc = "      \"enum\": ["]
#[doc = "        \"http\","]
#[doc = "        \"hls\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/MediaStreamProtocol\""]
#[doc = "        }"]
#[doc = "      ]"]
#[doc = "    },"]
#[doc = "    \"SegmentLength\": {"]
#[doc = "      \"description\": \"Gets or sets the segment length.\","]
#[doc = "      \"default\": 0,"]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\""]
#[doc = "    },"]
#[doc = "    \"TranscodeSeekInfo\": {"]
#[doc = "      \"description\": \"Gets or sets the transcoding seek info mode.\","]
#[doc = "      \"default\": \"Auto\","]
#[doc = "      \"enum\": ["]
#[doc = "        \"Auto\","]
#[doc = "        \"Bytes\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/TranscodeSeekInfo\""]
#[doc = "        }"]
#[doc = "      ]"]
#[doc = "    },"]
#[doc = "    \"Type\": {"]
#[doc = "      \"description\": \"Gets or sets the DLNA profile type.\","]
#[doc = "      \"enum\": ["]
#[doc = "        \"Audio\","]
#[doc = "        \"Video\","]
#[doc = "        \"Photo\","]
#[doc = "        \"Subtitle\","]
#[doc = "        \"Lyric\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/DlnaProfileType\""]
#[doc = "        }"]
#[doc = "      ]"]
#[doc = "    },"]
#[doc = "    \"VideoCodec\": {"]
#[doc = "      \"description\": \"Gets or sets the video codec.\","]
#[doc = "      \"type\": \"string\""]
#[doc = "    }"]
#[doc = "  }"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug)]
pub struct TranscodingProfile {
    #[doc = "Gets or sets the audio codec."]
    #[serde(
        rename = "AudioCodec",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub audio_codec: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets a value indicating whether breaking the video stream on non-keyframes is supported."]
    #[serde(rename = "BreakOnNonKeyFrames", default)]
    pub break_on_non_key_frames: bool,
    #[doc = "Gets or sets the profile conditions."]
    #[serde(
        rename = "Conditions",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub conditions: ::std::vec::Vec<ProfileCondition>,
    #[doc = "Gets or sets the container."]
    #[serde(
        rename = "Container",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub container: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "Context",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub context: ::std::option::Option<EncodingContext>,
    #[doc = "Gets or sets a value indicating whether timestamps should be copied."]
    #[serde(rename = "CopyTimestamps", default)]
    pub copy_timestamps: bool,
    #[doc = "Gets or sets a value indicating whether variable bitrate encoding is supported."]
    #[serde(
        rename = "EnableAudioVbrEncoding",
        default = "defaults::default_bool::<true>"
    )]
    pub enable_audio_vbr_encoding: bool,
    #[doc = "Gets or sets a value indicating whether M2TS mode is enabled."]
    #[serde(rename = "EnableMpegtsM2TsMode", default)]
    pub enable_mpegts_m2_ts_mode: bool,
    #[doc = "Gets or sets a value indicating whether subtitles are allowed in the manifest."]
    #[serde(rename = "EnableSubtitlesInManifest", default)]
    pub enable_subtitles_in_manifest: bool,
    #[doc = "Gets or sets a value indicating whether the content length should be estimated."]
    #[serde(rename = "EstimateContentLength", default)]
    pub estimate_content_length: bool,
    #[doc = "Gets or sets the maximum audio channels."]
    #[serde(
        rename = "MaxAudioChannels",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub max_audio_channels: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the minimum amount of segments."]
    #[serde(rename = "MinSegments", default)]
    pub min_segments: i32,
    #[serde(
        rename = "Protocol",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub protocol: ::std::option::Option<MediaStreamProtocol>,
    #[doc = "Gets or sets the segment length."]
    #[serde(rename = "SegmentLength", default)]
    pub segment_length: i32,
    #[serde(
        rename = "TranscodeSeekInfo",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub transcode_seek_info: ::std::option::Option<TranscodeSeekInfo>,
    #[serde(
        rename = "Type",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub type_: ::std::option::Option<DlnaProfileType>,
    #[doc = "Gets or sets the video codec."]
    #[serde(
        rename = "VideoCodec",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub video_codec: ::std::option::Option<::std::string::String>,
}
impl ::std::default::Default for TranscodingProfile {
    fn default() -> Self {
        Self {
            audio_codec: Default::default(),
            break_on_non_key_frames: Default::default(),
            conditions: Default::default(),
            container: Default::default(),
            context: Default::default(),
            copy_timestamps: Default::default(),
            enable_audio_vbr_encoding: defaults::default_bool::<true>(),
            enable_mpegts_m2_ts_mode: Default::default(),
            enable_subtitles_in_manifest: Default::default(),
            estimate_content_length: Default::default(),
            max_audio_channels: Default::default(),
            min_segments: Default::default(),
            protocol: Default::default(),
            segment_length: Default::default(),
            transcode_seek_info: Default::default(),
            type_: Default::default(),
            video_codec: Default::default(),
        }
    }
}
#[doc = "The type of timestamps used in a transport stream."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"The type of timestamps used in a transport stream.\","]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"None\","]
#[doc = "    \"Zero\","]
#[doc = "    \"Valid\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum TransportStreamTimestamp {
    None,
    Zero,
    Valid,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for TransportStreamTimestamp {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::None => f.write_str("None"),
            Self::Zero => f.write_str("Zero"),
            Self::Valid => f.write_str("Valid"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for TransportStreamTimestamp {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "None" => Ok(Self::None),
            "Zero" => Ok(Self::Zero),
            "Valid" => Ok(Self::Valid),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for TransportStreamTimestamp {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for TransportStreamTimestamp {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for TransportStreamTimestamp {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "The trickplay api model."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"The trickplay api model.\","]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"properties\": {"]
#[doc = "    \"Bandwidth\": {"]
#[doc = "      \"description\": \"Gets the peak bandwidth usage in bits per second.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\""]
#[doc = "    },"]
#[doc = "    \"Height\": {"]
#[doc = "      \"description\": \"Gets the height of an individual thumbnail.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\""]
#[doc = "    },"]
#[doc = "    \"Interval\": {"]
#[doc = "      \"description\": \"Gets the interval in milliseconds between each trickplay thumbnail.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\""]
#[doc = "    },"]
#[doc = "    \"ThumbnailCount\": {"]
#[doc = "      \"description\": \"Gets the total amount of non-black thumbnails.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\""]
#[doc = "    },"]
#[doc = "    \"TileHeight\": {"]
#[doc = "      \"description\": \"Gets the amount of thumbnails per column.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\""]
#[doc = "    },"]
#[doc = "    \"TileWidth\": {"]
#[doc = "      \"description\": \"Gets the amount of thumbnails per row.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\""]
#[doc = "    },"]
#[doc = "    \"Width\": {"]
#[doc = "      \"description\": \"Gets the width of an individual thumbnail.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\""]
#[doc = "    }"]
#[doc = "  }"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug, Default)]
pub struct TrickplayInfoDto {
    #[doc = "Gets the peak bandwidth usage in bits per second."]
    #[serde(
        rename = "Bandwidth",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub bandwidth: ::std::option::Option<i32>,
    #[doc = "Gets the height of an individual thumbnail."]
    #[serde(
        rename = "Height",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub height: ::std::option::Option<i32>,
    #[doc = "Gets the interval in milliseconds between each trickplay thumbnail."]
    #[serde(
        rename = "Interval",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub interval: ::std::option::Option<i32>,
    #[doc = "Gets the total amount of non-black thumbnails."]
    #[serde(
        rename = "ThumbnailCount",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub thumbnail_count: ::std::option::Option<i32>,
    #[doc = "Gets the amount of thumbnails per column."]
    #[serde(
        rename = "TileHeight",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub tile_height: ::std::option::Option<i32>,
    #[doc = "Gets the amount of thumbnails per row."]
    #[serde(
        rename = "TileWidth",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub tile_width: ::std::option::Option<i32>,
    #[doc = "Gets the width of an individual thumbnail."]
    #[serde(
        rename = "Width",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub width: ::std::option::Option<i32>,
}
#[doc = "An enum representing an unrated item."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"An enum representing an unrated item.\","]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"Movie\","]
#[doc = "    \"Trailer\","]
#[doc = "    \"Series\","]
#[doc = "    \"Music\","]
#[doc = "    \"Book\","]
#[doc = "    \"LiveTvChannel\","]
#[doc = "    \"LiveTvProgram\","]
#[doc = "    \"ChannelContent\","]
#[doc = "    \"Other\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum UnratedItem {
    Movie,
    Trailer,
    Series,
    Music,
    Book,
    LiveTvChannel,
    LiveTvProgram,
    ChannelContent,
    Other,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for UnratedItem {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::Movie => f.write_str("Movie"),
            Self::Trailer => f.write_str("Trailer"),
            Self::Series => f.write_str("Series"),
            Self::Music => f.write_str("Music"),
            Self::Book => f.write_str("Book"),
            Self::LiveTvChannel => f.write_str("LiveTvChannel"),
            Self::LiveTvProgram => f.write_str("LiveTvProgram"),
            Self::ChannelContent => f.write_str("ChannelContent"),
            Self::Other => f.write_str("Other"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for UnratedItem {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "Movie" => Ok(Self::Movie),
            "Trailer" => Ok(Self::Trailer),
            "Series" => Ok(Self::Series),
            "Music" => Ok(Self::Music),
            "Book" => Ok(Self::Book),
            "LiveTvChannel" => Ok(Self::LiveTvChannel),
            "LiveTvProgram" => Ok(Self::LiveTvProgram),
            "ChannelContent" => Ok(Self::ChannelContent),
            "Other" => Ok(Self::Other),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for UnratedItem {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for UnratedItem {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for UnratedItem {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "Class UserConfiguration."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"Class UserConfiguration.\","]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"properties\": {"]
#[doc = "    \"AudioLanguagePreference\": {"]
#[doc = "      \"description\": \"Gets or sets the audio language preference.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"CastReceiverId\": {"]
#[doc = "      \"description\": \"Gets or sets the id of the selected cast receiver.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"DisplayCollectionsView\": {"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"DisplayMissingEpisodes\": {"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"EnableLocalPassword\": {"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"EnableNextEpisodeAutoPlay\": {"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"GroupedFolders\": {"]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"type\": \"string\","]
#[doc = "        \"format\": \"uuid\""]
#[doc = "      }"]
#[doc = "    },"]
#[doc = "    \"HidePlayedInLatest\": {"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"LatestItemsExcludes\": {"]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"type\": \"string\","]
#[doc = "        \"format\": \"uuid\""]
#[doc = "      }"]
#[doc = "    },"]
#[doc = "    \"MyMediaExcludes\": {"]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"type\": \"string\","]
#[doc = "        \"format\": \"uuid\""]
#[doc = "      }"]
#[doc = "    },"]
#[doc = "    \"OrderedViews\": {"]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"type\": \"string\","]
#[doc = "        \"format\": \"uuid\""]
#[doc = "      }"]
#[doc = "    },"]
#[doc = "    \"PlayDefaultAudioTrack\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether [play default audio track].\","]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"RememberAudioSelections\": {"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"RememberSubtitleSelections\": {"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"SubtitleLanguagePreference\": {"]
#[doc = "      \"description\": \"Gets or sets the subtitle language preference.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"SubtitleMode\": {"]
#[doc = "      \"description\": \"An enum representing a subtitle playback mode.\","]
#[doc = "      \"enum\": ["]
#[doc = "        \"Default\","]
#[doc = "        \"Always\","]
#[doc = "        \"OnlyForced\","]
#[doc = "        \"None\","]
#[doc = "        \"Smart\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/SubtitlePlaybackMode\""]
#[doc = "        }"]
#[doc = "      ]"]
#[doc = "    }"]
#[doc = "  }"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug, Default)]
pub struct UserConfiguration {
    #[doc = "Gets or sets the audio language preference."]
    #[serde(
        rename = "AudioLanguagePreference",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub audio_language_preference: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the id of the selected cast receiver."]
    #[serde(
        rename = "CastReceiverId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub cast_receiver_id: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "DisplayCollectionsView",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub display_collections_view: ::std::option::Option<bool>,
    #[serde(
        rename = "DisplayMissingEpisodes",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub display_missing_episodes: ::std::option::Option<bool>,
    #[serde(
        rename = "EnableLocalPassword",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub enable_local_password: ::std::option::Option<bool>,
    #[serde(
        rename = "EnableNextEpisodeAutoPlay",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub enable_next_episode_auto_play: ::std::option::Option<bool>,
    #[serde(
        rename = "GroupedFolders",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub grouped_folders: ::std::vec::Vec<::uuid::Uuid>,
    #[serde(
        rename = "HidePlayedInLatest",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub hide_played_in_latest: ::std::option::Option<bool>,
    #[serde(
        rename = "LatestItemsExcludes",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub latest_items_excludes: ::std::vec::Vec<::uuid::Uuid>,
    #[serde(
        rename = "MyMediaExcludes",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub my_media_excludes: ::std::vec::Vec<::uuid::Uuid>,
    #[serde(
        rename = "OrderedViews",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub ordered_views: ::std::vec::Vec<::uuid::Uuid>,
    #[doc = "Gets or sets a value indicating whether [play default audio track]."]
    #[serde(
        rename = "PlayDefaultAudioTrack",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub play_default_audio_track: ::std::option::Option<bool>,
    #[serde(
        rename = "RememberAudioSelections",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub remember_audio_selections: ::std::option::Option<bool>,
    #[serde(
        rename = "RememberSubtitleSelections",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub remember_subtitle_selections: ::std::option::Option<bool>,
    #[doc = "Gets or sets the subtitle language preference."]
    #[serde(
        rename = "SubtitleLanguagePreference",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub subtitle_language_preference: ::std::option::Option<::std::string::String>,
    #[serde(
        rename = "SubtitleMode",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub subtitle_mode: ::std::option::Option<SubtitlePlaybackMode>,
}
#[doc = "Class UserDataChangeInfo."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"Class UserDataChangeInfo.\","]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"properties\": {"]
#[doc = "    \"UserDataList\": {"]
#[doc = "      \"description\": \"Gets or sets the user data list.\","]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"$ref\": \"#/$defs/UserItemDataDto\""]
#[doc = "      }"]
#[doc = "    },"]
#[doc = "    \"UserId\": {"]
#[doc = "      \"description\": \"Gets or sets the user id.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"format\": \"uuid\""]
#[doc = "    }"]
#[doc = "  }"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug, Default)]
pub struct UserDataChangeInfo {
    #[doc = "Gets or sets the user data list."]
    #[serde(
        rename = "UserDataList",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub user_data_list: ::std::vec::Vec<UserItemDataDto>,
    #[doc = "Gets or sets the user id."]
    #[serde(
        rename = "UserId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub user_id: ::std::option::Option<::uuid::Uuid>,
}
#[doc = "Class UserDto."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"Class UserDto.\","]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"properties\": {"]
#[doc = "    \"Configuration\": {"]
#[doc = "      \"description\": \"Gets or sets the configuration.\","]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/UserConfiguration\""]
#[doc = "        }"]
#[doc = "      ],"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"EnableAutoLogin\": {"]
#[doc = "      \"description\": \"Gets or sets whether async login is enabled or not.\","]
#[doc = "      \"type\": \"boolean\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"HasConfiguredEasyPassword\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether this instance has configured easy password.\","]
#[doc = "      \"deprecated\": true,"]
#[doc = "      \"type\": \"boolean\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"HasConfiguredPassword\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether this instance has configured password.\","]
#[doc = "      \"deprecated\": true,"]
#[doc = "      \"type\": \"boolean\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"HasPassword\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether this instance has password.\","]
#[doc = "      \"deprecated\": true,"]
#[doc = "      \"type\": \"boolean\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Id\": {"]
#[doc = "      \"description\": \"Gets or sets the id.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"format\": \"uuid\""]
#[doc = "    },"]
#[doc = "    \"LastActivityDate\": {"]
#[doc = "      \"description\": \"Gets or sets the last activity date.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"format\": \"date-time\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"LastLoginDate\": {"]
#[doc = "      \"description\": \"Gets or sets the last login date.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"format\": \"date-time\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Name\": {"]
#[doc = "      \"description\": \"Gets or sets the name.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Policy\": {"]
#[doc = "      \"description\": \"Gets or sets the policy.\","]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/UserPolicy\""]
#[doc = "        }"]
#[doc = "      ],"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"PrimaryImageAspectRatio\": {"]
#[doc = "      \"description\": \"Gets or sets the primary image aspect ratio.\","]
#[doc = "      \"type\": \"number\","]
#[doc = "      \"format\": \"double\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"PrimaryImageTag\": {"]
#[doc = "      \"description\": \"Gets or sets the primary image tag.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ServerId\": {"]
#[doc = "      \"description\": \"Gets or sets the server identifier.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ServerName\": {"]
#[doc = "      \"description\": \"Gets or sets the name of the server.\\nThis is not used by the server and is for client-side usage only.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"nullable\": true"]
#[doc = "    }"]
#[doc = "  }"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug, Default)]
pub struct UserDto {
    #[doc = "Gets or sets the configuration."]
    #[serde(
        rename = "Configuration",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub configuration: ::std::option::Option<UserConfiguration>,
    #[doc = "Gets or sets whether async login is enabled or not."]
    #[serde(
        rename = "EnableAutoLogin",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub enable_auto_login: ::std::option::Option<bool>,
    #[doc = "Gets or sets a value indicating whether this instance has configured easy password."]
    #[serde(
        rename = "HasConfiguredEasyPassword",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub has_configured_easy_password: ::std::option::Option<bool>,
    #[doc = "Gets or sets a value indicating whether this instance has configured password."]
    #[serde(
        rename = "HasConfiguredPassword",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub has_configured_password: ::std::option::Option<bool>,
    #[doc = "Gets or sets a value indicating whether this instance has password."]
    #[serde(
        rename = "HasPassword",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub has_password: ::std::option::Option<bool>,
    #[doc = "Gets or sets the id."]
    #[serde(
        rename = "Id",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub id: ::std::option::Option<::uuid::Uuid>,
    #[doc = "Gets or sets the last activity date."]
    #[serde(
        rename = "LastActivityDate",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub last_activity_date: ::std::option::Option<::chrono::DateTime<::chrono::offset::Utc>>,
    #[doc = "Gets or sets the last login date."]
    #[serde(
        rename = "LastLoginDate",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub last_login_date: ::std::option::Option<::chrono::DateTime<::chrono::offset::Utc>>,
    #[doc = "Gets or sets the name."]
    #[serde(
        rename = "Name",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub name: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the policy."]
    #[serde(
        rename = "Policy",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub policy: ::std::option::Option<UserPolicy>,
    #[doc = "Gets or sets the primary image aspect ratio."]
    #[serde(
        rename = "PrimaryImageAspectRatio",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub primary_image_aspect_ratio: ::std::option::Option<f64>,
    #[doc = "Gets or sets the primary image tag."]
    #[serde(
        rename = "PrimaryImageTag",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub primary_image_tag: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the server identifier."]
    #[serde(
        rename = "ServerId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub server_id: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the name of the server.\nThis is not used by the server and is for client-side usage only."]
    #[serde(
        rename = "ServerName",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub server_name: ::std::option::Option<::std::string::String>,
}
#[doc = "Class UserItemDataDto."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"Class UserItemDataDto.\","]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"properties\": {"]
#[doc = "    \"IsFavorite\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether this instance is favorite.\","]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"ItemId\": {"]
#[doc = "      \"description\": \"Gets or sets the item identifier.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"format\": \"uuid\""]
#[doc = "    },"]
#[doc = "    \"Key\": {"]
#[doc = "      \"description\": \"Gets or sets the key.\","]
#[doc = "      \"type\": \"string\""]
#[doc = "    },"]
#[doc = "    \"LastPlayedDate\": {"]
#[doc = "      \"description\": \"Gets or sets the last played date.\","]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"format\": \"date-time\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Likes\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether this MediaBrowser.Model.Dto.UserItemDataDto is likes.\","]
#[doc = "      \"type\": \"boolean\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"PlayCount\": {"]
#[doc = "      \"description\": \"Gets or sets the play count.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\""]
#[doc = "    },"]
#[doc = "    \"PlaybackPositionTicks\": {"]
#[doc = "      \"description\": \"Gets or sets the playback position ticks.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int64\""]
#[doc = "    },"]
#[doc = "    \"Played\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether this MediaBrowser.Model.Dto.UserItemDataDto is played.\","]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"PlayedPercentage\": {"]
#[doc = "      \"description\": \"Gets or sets the played percentage.\","]
#[doc = "      \"type\": \"number\","]
#[doc = "      \"format\": \"double\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"Rating\": {"]
#[doc = "      \"description\": \"Gets or sets the rating.\","]
#[doc = "      \"type\": \"number\","]
#[doc = "      \"format\": \"double\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"UnplayedItemCount\": {"]
#[doc = "      \"description\": \"Gets or sets the unplayed item count.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    }"]
#[doc = "  }"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug, Default)]
pub struct UserItemDataDto {
    #[doc = "Gets or sets a value indicating whether this instance is favorite."]
    #[serde(
        rename = "IsFavorite",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub is_favorite: ::std::option::Option<bool>,
    #[doc = "Gets or sets the item identifier."]
    #[serde(
        rename = "ItemId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub item_id: ::std::option::Option<::uuid::Uuid>,
    #[doc = "Gets or sets the key."]
    #[serde(
        rename = "Key",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub key: ::std::option::Option<::std::string::String>,
    #[doc = "Gets or sets the last played date."]
    #[serde(
        rename = "LastPlayedDate",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub last_played_date: ::std::option::Option<::chrono::DateTime<::chrono::offset::Utc>>,
    #[doc = "Gets or sets a value indicating whether this MediaBrowser.Model.Dto.UserItemDataDto is likes."]
    #[serde(
        rename = "Likes",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub likes: ::std::option::Option<bool>,
    #[doc = "Gets or sets the play count."]
    #[serde(
        rename = "PlayCount",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub play_count: ::std::option::Option<i32>,
    #[doc = "Gets or sets the playback position ticks."]
    #[serde(
        rename = "PlaybackPositionTicks",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub playback_position_ticks: ::std::option::Option<i64>,
    #[doc = "Gets or sets a value indicating whether this MediaBrowser.Model.Dto.UserItemDataDto is played."]
    #[serde(
        rename = "Played",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub played: ::std::option::Option<bool>,
    #[doc = "Gets or sets the played percentage."]
    #[serde(
        rename = "PlayedPercentage",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub played_percentage: ::std::option::Option<f64>,
    #[doc = "Gets or sets the rating."]
    #[serde(
        rename = "Rating",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub rating: ::std::option::Option<f64>,
    #[doc = "Gets or sets the unplayed item count."]
    #[serde(
        rename = "UnplayedItemCount",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub unplayed_item_count: ::std::option::Option<i32>,
}
#[doc = "`UserPolicy`"]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"type\": \"object\","]
#[doc = "  \"properties\": {"]
#[doc = "    \"AccessSchedules\": {"]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"$ref\": \"#/$defs/AccessSchedule\""]
#[doc = "      },"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"AllowedTags\": {"]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"type\": \"string\""]
#[doc = "      },"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"AuthenticationProviderId\": {"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"minLength\": 1"]
#[doc = "    },"]
#[doc = "    \"BlockUnratedItems\": {"]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"$ref\": \"#/$defs/UnratedItem\""]
#[doc = "      },"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"BlockedChannels\": {"]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"type\": \"string\","]
#[doc = "        \"format\": \"uuid\""]
#[doc = "      },"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"BlockedMediaFolders\": {"]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"type\": \"string\","]
#[doc = "        \"format\": \"uuid\""]
#[doc = "      },"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"BlockedTags\": {"]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"type\": \"string\""]
#[doc = "      },"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"EnableAllChannels\": {"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"EnableAllDevices\": {"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"EnableAllFolders\": {"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"EnableAudioPlaybackTranscoding\": {"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"EnableCollectionManagement\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether this instance can manage collections.\","]
#[doc = "      \"default\": false,"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"EnableContentDeletion\": {"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"EnableContentDeletionFromFolders\": {"]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"type\": \"string\""]
#[doc = "      },"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"EnableContentDownloading\": {"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"EnableLiveTvAccess\": {"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"EnableLiveTvManagement\": {"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"EnableLyricManagement\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether this user can manage lyrics.\","]
#[doc = "      \"default\": false,"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"EnableMediaConversion\": {"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"EnableMediaPlayback\": {"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"EnablePlaybackRemuxing\": {"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"EnablePublicSharing\": {"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"EnableRemoteAccess\": {"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"EnableRemoteControlOfOtherUsers\": {"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"EnableSharedDeviceControl\": {"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"EnableSubtitleManagement\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether this instance can manage subtitles.\","]
#[doc = "      \"default\": false,"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"EnableSyncTranscoding\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether [enable synchronize].\","]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"EnableUserPreferenceAccess\": {"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"EnableVideoPlaybackTranscoding\": {"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"EnabledChannels\": {"]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"type\": \"string\","]
#[doc = "        \"format\": \"uuid\""]
#[doc = "      },"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"EnabledDevices\": {"]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"type\": \"string\""]
#[doc = "      },"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"EnabledFolders\": {"]
#[doc = "      \"type\": \"array\","]
#[doc = "      \"items\": {"]
#[doc = "        \"type\": \"string\","]
#[doc = "        \"format\": \"uuid\""]
#[doc = "      },"]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"ForceRemoteSourceTranscoding\": {"]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"InvalidLoginAttemptCount\": {"]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\""]
#[doc = "    },"]
#[doc = "    \"IsAdministrator\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether this instance is administrator.\","]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"IsDisabled\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether this instance is disabled.\","]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"IsHidden\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating whether this instance is hidden.\","]
#[doc = "      \"type\": \"boolean\""]
#[doc = "    },"]
#[doc = "    \"LoginAttemptsBeforeLockout\": {"]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\""]
#[doc = "    },"]
#[doc = "    \"MaxActiveSessions\": {"]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\""]
#[doc = "    },"]
#[doc = "    \"MaxParentalRating\": {"]
#[doc = "      \"description\": \"Gets or sets the max parental rating.\","]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"MaxParentalSubRating\": {"]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\","]
#[doc = "      \"nullable\": true"]
#[doc = "    },"]
#[doc = "    \"PasswordResetProviderId\": {"]
#[doc = "      \"type\": \"string\","]
#[doc = "      \"minLength\": 1"]
#[doc = "    },"]
#[doc = "    \"RemoteClientBitrateLimit\": {"]
#[doc = "      \"type\": \"integer\","]
#[doc = "      \"format\": \"int32\""]
#[doc = "    },"]
#[doc = "    \"SyncPlayAccess\": {"]
#[doc = "      \"description\": \"Gets or sets a value indicating what SyncPlay features the user can access.\","]
#[doc = "      \"enum\": ["]
#[doc = "        \"CreateAndJoinGroups\","]
#[doc = "        \"JoinGroups\","]
#[doc = "        \"None\""]
#[doc = "      ],"]
#[doc = "      \"allOf\": ["]
#[doc = "        {"]
#[doc = "          \"$ref\": \"#/$defs/SyncPlayUserAccessType\""]
#[doc = "        }"]
#[doc = "      ]"]
#[doc = "    }"]
#[doc = "  }"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Deserialize, :: serde :: Serialize, Clone, Debug, Default)]
pub struct UserPolicy {
    #[serde(
        rename = "AccessSchedules",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub access_schedules: ::std::vec::Vec<AccessSchedule>,
    #[serde(
        rename = "AllowedTags",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub allowed_tags: ::std::vec::Vec<::std::string::String>,
    #[serde(
        rename = "AuthenticationProviderId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub authentication_provider_id: ::std::option::Option<UserPolicyAuthenticationProviderId>,
    #[serde(
        rename = "BlockUnratedItems",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub block_unrated_items: ::std::vec::Vec<UnratedItem>,
    #[serde(
        rename = "BlockedChannels",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub blocked_channels: ::std::vec::Vec<::uuid::Uuid>,
    #[serde(
        rename = "BlockedMediaFolders",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub blocked_media_folders: ::std::vec::Vec<::uuid::Uuid>,
    #[serde(
        rename = "BlockedTags",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub blocked_tags: ::std::vec::Vec<::std::string::String>,
    #[serde(
        rename = "EnableAllChannels",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub enable_all_channels: ::std::option::Option<bool>,
    #[serde(
        rename = "EnableAllDevices",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub enable_all_devices: ::std::option::Option<bool>,
    #[serde(
        rename = "EnableAllFolders",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub enable_all_folders: ::std::option::Option<bool>,
    #[serde(
        rename = "EnableAudioPlaybackTranscoding",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub enable_audio_playback_transcoding: ::std::option::Option<bool>,
    #[doc = "Gets or sets a value indicating whether this instance can manage collections."]
    #[serde(rename = "EnableCollectionManagement", default)]
    pub enable_collection_management: bool,
    #[serde(
        rename = "EnableContentDeletion",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub enable_content_deletion: ::std::option::Option<bool>,
    #[serde(
        rename = "EnableContentDeletionFromFolders",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub enable_content_deletion_from_folders: ::std::vec::Vec<::std::string::String>,
    #[serde(
        rename = "EnableContentDownloading",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub enable_content_downloading: ::std::option::Option<bool>,
    #[serde(
        rename = "EnableLiveTvAccess",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub enable_live_tv_access: ::std::option::Option<bool>,
    #[serde(
        rename = "EnableLiveTvManagement",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub enable_live_tv_management: ::std::option::Option<bool>,
    #[doc = "Gets or sets a value indicating whether this user can manage lyrics."]
    #[serde(rename = "EnableLyricManagement", default)]
    pub enable_lyric_management: bool,
    #[serde(
        rename = "EnableMediaConversion",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub enable_media_conversion: ::std::option::Option<bool>,
    #[serde(
        rename = "EnableMediaPlayback",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub enable_media_playback: ::std::option::Option<bool>,
    #[serde(
        rename = "EnablePlaybackRemuxing",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub enable_playback_remuxing: ::std::option::Option<bool>,
    #[serde(
        rename = "EnablePublicSharing",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub enable_public_sharing: ::std::option::Option<bool>,
    #[serde(
        rename = "EnableRemoteAccess",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub enable_remote_access: ::std::option::Option<bool>,
    #[serde(
        rename = "EnableRemoteControlOfOtherUsers",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub enable_remote_control_of_other_users: ::std::option::Option<bool>,
    #[serde(
        rename = "EnableSharedDeviceControl",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub enable_shared_device_control: ::std::option::Option<bool>,
    #[doc = "Gets or sets a value indicating whether this instance can manage subtitles."]
    #[serde(rename = "EnableSubtitleManagement", default)]
    pub enable_subtitle_management: bool,
    #[doc = "Gets or sets a value indicating whether [enable synchronize]."]
    #[serde(
        rename = "EnableSyncTranscoding",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub enable_sync_transcoding: ::std::option::Option<bool>,
    #[serde(
        rename = "EnableUserPreferenceAccess",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub enable_user_preference_access: ::std::option::Option<bool>,
    #[serde(
        rename = "EnableVideoPlaybackTranscoding",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub enable_video_playback_transcoding: ::std::option::Option<bool>,
    #[serde(
        rename = "EnabledChannels",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub enabled_channels: ::std::vec::Vec<::uuid::Uuid>,
    #[serde(
        rename = "EnabledDevices",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub enabled_devices: ::std::vec::Vec<::std::string::String>,
    #[serde(
        rename = "EnabledFolders",
        default,
        skip_serializing_if = "::std::vec::Vec::is_empty"
    )]
    pub enabled_folders: ::std::vec::Vec<::uuid::Uuid>,
    #[serde(
        rename = "ForceRemoteSourceTranscoding",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub force_remote_source_transcoding: ::std::option::Option<bool>,
    #[serde(
        rename = "InvalidLoginAttemptCount",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub invalid_login_attempt_count: ::std::option::Option<i32>,
    #[doc = "Gets or sets a value indicating whether this instance is administrator."]
    #[serde(
        rename = "IsAdministrator",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub is_administrator: ::std::option::Option<bool>,
    #[doc = "Gets or sets a value indicating whether this instance is disabled."]
    #[serde(
        rename = "IsDisabled",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub is_disabled: ::std::option::Option<bool>,
    #[doc = "Gets or sets a value indicating whether this instance is hidden."]
    #[serde(
        rename = "IsHidden",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub is_hidden: ::std::option::Option<bool>,
    #[serde(
        rename = "LoginAttemptsBeforeLockout",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub login_attempts_before_lockout: ::std::option::Option<i32>,
    #[serde(
        rename = "MaxActiveSessions",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub max_active_sessions: ::std::option::Option<i32>,
    #[doc = "Gets or sets the max parental rating."]
    #[serde(
        rename = "MaxParentalRating",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub max_parental_rating: ::std::option::Option<i32>,
    #[serde(
        rename = "MaxParentalSubRating",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub max_parental_sub_rating: ::std::option::Option<i32>,
    #[serde(
        rename = "PasswordResetProviderId",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub password_reset_provider_id: ::std::option::Option<UserPolicyPasswordResetProviderId>,
    #[serde(
        rename = "RemoteClientBitrateLimit",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub remote_client_bitrate_limit: ::std::option::Option<i32>,
    #[serde(
        rename = "SyncPlayAccess",
        default,
        skip_serializing_if = "::std::option::Option::is_none"
    )]
    pub sync_play_access: ::std::option::Option<SyncPlayUserAccessType>,
}
#[doc = "`UserPolicyAuthenticationProviderId`"]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"minLength\": 1"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Serialize, Clone, Debug, Eq, Hash, Ord, PartialEq, PartialOrd)]
#[serde(transparent)]
pub struct UserPolicyAuthenticationProviderId(::std::string::String);
impl ::std::ops::Deref for UserPolicyAuthenticationProviderId {
    type Target = ::std::string::String;
    fn deref(&self) -> &::std::string::String {
        &self.0
    }
}
impl ::std::convert::From<UserPolicyAuthenticationProviderId> for ::std::string::String {
    fn from(value: UserPolicyAuthenticationProviderId) -> Self {
        value.0
    }
}
impl ::std::str::FromStr for UserPolicyAuthenticationProviderId {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        if value.chars().count() < 1usize {
            return Err("shorter than 1 characters".into());
        }
        Ok(Self(value.to_string()))
    }
}
impl ::std::convert::TryFrom<&str> for UserPolicyAuthenticationProviderId {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for UserPolicyAuthenticationProviderId {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for UserPolicyAuthenticationProviderId {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl<'de> ::serde::Deserialize<'de> for UserPolicyAuthenticationProviderId {
    fn deserialize<D>(deserializer: D) -> ::std::result::Result<Self, D::Error>
    where
        D: ::serde::Deserializer<'de>,
    {
        ::std::string::String::deserialize(deserializer)?
            .parse()
            .map_err(|e: self::error::ConversionError| {
                <D::Error as ::serde::de::Error>::custom(e.to_string())
            })
    }
}
#[doc = "`UserPolicyPasswordResetProviderId`"]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"minLength\": 1"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(:: serde :: Serialize, Clone, Debug, Eq, Hash, Ord, PartialEq, PartialOrd)]
#[serde(transparent)]
pub struct UserPolicyPasswordResetProviderId(::std::string::String);
impl ::std::ops::Deref for UserPolicyPasswordResetProviderId {
    type Target = ::std::string::String;
    fn deref(&self) -> &::std::string::String {
        &self.0
    }
}
impl ::std::convert::From<UserPolicyPasswordResetProviderId> for ::std::string::String {
    fn from(value: UserPolicyPasswordResetProviderId) -> Self {
        value.0
    }
}
impl ::std::str::FromStr for UserPolicyPasswordResetProviderId {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        if value.chars().count() < 1usize {
            return Err("shorter than 1 characters".into());
        }
        Ok(Self(value.to_string()))
    }
}
impl ::std::convert::TryFrom<&str> for UserPolicyPasswordResetProviderId {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for UserPolicyPasswordResetProviderId {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for UserPolicyPasswordResetProviderId {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl<'de> ::serde::Deserialize<'de> for UserPolicyPasswordResetProviderId {
    fn deserialize<D>(deserializer: D) -> ::std::result::Result<Self, D::Error>
    where
        D: ::serde::Deserializer<'de>,
    {
        ::std::string::String::deserialize(deserializer)?
            .parse()
            .map_err(|e: self::error::ConversionError| {
                <D::Error as ::serde::de::Error>::custom(e.to_string())
            })
    }
}
#[doc = "`Video3DFormat`"]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"HalfSideBySide\","]
#[doc = "    \"FullSideBySide\","]
#[doc = "    \"FullTopAndBottom\","]
#[doc = "    \"HalfTopAndBottom\","]
#[doc = "    \"MVC\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum Video3DFormat {
    HalfSideBySide,
    FullSideBySide,
    FullTopAndBottom,
    HalfTopAndBottom,
    #[serde(rename = "MVC")]
    Mvc,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for Video3DFormat {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::HalfSideBySide => f.write_str("HalfSideBySide"),
            Self::FullSideBySide => f.write_str("FullSideBySide"),
            Self::FullTopAndBottom => f.write_str("FullTopAndBottom"),
            Self::HalfTopAndBottom => f.write_str("HalfTopAndBottom"),
            Self::Mvc => f.write_str("MVC"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for Video3DFormat {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "HalfSideBySide" => Ok(Self::HalfSideBySide),
            "FullSideBySide" => Ok(Self::FullSideBySide),
            "FullTopAndBottom" => Ok(Self::FullTopAndBottom),
            "HalfTopAndBottom" => Ok(Self::HalfTopAndBottom),
            "MVC" => Ok(Self::Mvc),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for Video3DFormat {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for Video3DFormat {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for Video3DFormat {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "An enum representing video ranges."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"An enum representing video ranges.\","]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"Unknown\","]
#[doc = "    \"SDR\","]
#[doc = "    \"HDR\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum VideoRange {
    Unknown,
    #[serde(rename = "SDR")]
    Sdr,
    #[serde(rename = "HDR")]
    Hdr,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for VideoRange {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::Unknown => f.write_str("Unknown"),
            Self::Sdr => f.write_str("SDR"),
            Self::Hdr => f.write_str("HDR"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for VideoRange {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "Unknown" => Ok(Self::Unknown),
            "SDR" => Ok(Self::Sdr),
            "HDR" => Ok(Self::Hdr),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for VideoRange {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for VideoRange {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for VideoRange {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "An enum representing types of video ranges."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"An enum representing types of video ranges.\","]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"Unknown\","]
#[doc = "    \"SDR\","]
#[doc = "    \"HDR10\","]
#[doc = "    \"HLG\","]
#[doc = "    \"DOVI\","]
#[doc = "    \"DOVIWithHDR10\","]
#[doc = "    \"DOVIWithHLG\","]
#[doc = "    \"DOVIWithSDR\","]
#[doc = "    \"DOVIWithEL\","]
#[doc = "    \"DOVIWithHDR10Plus\","]
#[doc = "    \"DOVIWithELHDR10Plus\","]
#[doc = "    \"DOVIInvalid\","]
#[doc = "    \"HDR10Plus\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum VideoRangeType {
    Unknown,
    #[serde(rename = "SDR")]
    Sdr,
    #[serde(rename = "HDR10")]
    Hdr10,
    #[serde(rename = "HLG")]
    Hlg,
    #[serde(rename = "DOVI")]
    Dovi,
    #[serde(rename = "DOVIWithHDR10")]
    DoviWithHdr10,
    #[serde(rename = "DOVIWithHLG")]
    DoviWithHlg,
    #[serde(rename = "DOVIWithSDR")]
    DoviWithSdr,
    #[serde(rename = "DOVIWithEL")]
    DoviWithEl,
    #[serde(rename = "DOVIWithHDR10Plus")]
    DoviWithHdr10Plus,
    #[serde(rename = "DOVIWithELHDR10Plus")]
    DoviWithElhdr10Plus,
    #[serde(rename = "DOVIInvalid")]
    DoviInvalid,
    #[serde(rename = "HDR10Plus")]
    Hdr10Plus,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for VideoRangeType {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::Unknown => f.write_str("Unknown"),
            Self::Sdr => f.write_str("SDR"),
            Self::Hdr10 => f.write_str("HDR10"),
            Self::Hlg => f.write_str("HLG"),
            Self::Dovi => f.write_str("DOVI"),
            Self::DoviWithHdr10 => f.write_str("DOVIWithHDR10"),
            Self::DoviWithHlg => f.write_str("DOVIWithHLG"),
            Self::DoviWithSdr => f.write_str("DOVIWithSDR"),
            Self::DoviWithEl => f.write_str("DOVIWithEL"),
            Self::DoviWithHdr10Plus => f.write_str("DOVIWithHDR10Plus"),
            Self::DoviWithElhdr10Plus => f.write_str("DOVIWithELHDR10Plus"),
            Self::DoviInvalid => f.write_str("DOVIInvalid"),
            Self::Hdr10Plus => f.write_str("HDR10Plus"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for VideoRangeType {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "Unknown" => Ok(Self::Unknown),
            "SDR" => Ok(Self::Sdr),
            "HDR10" => Ok(Self::Hdr10),
            "HLG" => Ok(Self::Hlg),
            "DOVI" => Ok(Self::Dovi),
            "DOVIWithHDR10" => Ok(Self::DoviWithHdr10),
            "DOVIWithHLG" => Ok(Self::DoviWithHlg),
            "DOVIWithSDR" => Ok(Self::DoviWithSdr),
            "DOVIWithEL" => Ok(Self::DoviWithEl),
            "DOVIWithHDR10Plus" => Ok(Self::DoviWithHdr10Plus),
            "DOVIWithELHDR10Plus" => Ok(Self::DoviWithElhdr10Plus),
            "DOVIInvalid" => Ok(Self::DoviInvalid),
            "HDR10Plus" => Ok(Self::Hdr10Plus),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for VideoRangeType {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for VideoRangeType {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for VideoRangeType {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = "Enum VideoType."]
#[doc = r""]
#[doc = r" <details><summary>JSON schema</summary>"]
#[doc = r""]
#[doc = r" ```json"]
#[doc = "{"]
#[doc = "  \"description\": \"Enum VideoType.\","]
#[doc = "  \"type\": \"string\","]
#[doc = "  \"enum\": ["]
#[doc = "    \"VideoFile\","]
#[doc = "    \"Iso\","]
#[doc = "    \"Dvd\","]
#[doc = "    \"BluRay\""]
#[doc = "  ]"]
#[doc = "}"]
#[doc = r" ```"]
#[doc = r" </details>"]
#[derive(
    :: serde :: Deserialize,
    :: serde :: Serialize,
    Clone,
    Copy,
    Debug,
    Eq,
    Hash,
    Ord,
    PartialEq,
    PartialOrd,
)]
pub enum VideoType {
    VideoFile,
    Iso,
    Dvd,
    BluRay,
    #[serde(other)]
    Unrecognized,
}
impl ::std::fmt::Display for VideoType {
    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {
        match *self {
            Self::VideoFile => f.write_str("VideoFile"),
            Self::Iso => f.write_str("Iso"),
            Self::Dvd => f.write_str("Dvd"),
            Self::BluRay => f.write_str("BluRay"),
            Self::Unrecognized => f.write_str("Unrecognized"),
        }
    }
}
impl ::std::str::FromStr for VideoType {
    type Err = self::error::ConversionError;
    fn from_str(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        match value {
            "VideoFile" => Ok(Self::VideoFile),
            "Iso" => Ok(Self::Iso),
            "Dvd" => Ok(Self::Dvd),
            "BluRay" => Ok(Self::BluRay),
            _ => Err("invalid value".into()),
        }
    }
}
impl ::std::convert::TryFrom<&str> for VideoType {
    type Error = self::error::ConversionError;
    fn try_from(value: &str) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<&::std::string::String> for VideoType {
    type Error = self::error::ConversionError;
    fn try_from(
        value: &::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
impl ::std::convert::TryFrom<::std::string::String> for VideoType {
    type Error = self::error::ConversionError;
    fn try_from(
        value: ::std::string::String,
    ) -> ::std::result::Result<Self, self::error::ConversionError> {
        value.parse()
    }
}
#[doc = r" Generation of default values for serde."]
pub mod defaults {
    pub(super) fn default_bool<const V: bool>() -> bool {
        V
    }
}

/// Alias for the frozen jellyfin-api client interface (lib.rs uses
/// `ItemsResult`; the OpenAPI schema calls this `BaseItemDtoQueryResult`).
pub type ItemsResult = BaseItemDtoQueryResult;
