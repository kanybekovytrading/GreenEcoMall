package greenecomall.enums;

public enum MediaFileType {
    /** User profile avatar → avatars/{userId}/{uuid}.ext */
    AVATAR,
    /** News article cover image → news/{uuid}.ext */
    NEWS_COVER,
    /** Media attachments inside a news post → news/media/{uuid}.ext */
    NEWS_MEDIA,
    /** Video lesson thumbnail → thumbnails/{uuid}.ext */
    VIDEO_THUMBNAIL,
    /** Test icon → tests/{uuid}.ext */
    TEST_ICON,
    /** Question image → questions/{uuid}.ext */
    QUESTION_IMAGE
}
