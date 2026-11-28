// Android original, 34 conditions, as verified in the iOS port deep-dive §8.
const SYMBOLS: [(&str, &str); 34] = [
    ("blowingDust", "wind"),
    ("clear", "sun.max.fill"),
    ("cloudy", "cloud.fill"),
    ("foggy", "cloud.fog.fill"),
    ("haze", "sun.haze.fill"),
    ("mostlyClear", "sun.max.fill"),
    ("mostlyCloudy", "cloud.fill"),
    ("partlyCloudy", "cloud.sun.fill"),
    ("smoky", "smoke.fill"),
    ("breezy", "wind"),
    ("windy", "wind"),
    ("drizzle", "cloud.drizzle.fill"),
    ("heavyRain", "cloud.heavyrain.fill"),
    ("isolatedThunderstorms", "cloud.bolt.fill"),
    ("rain", "cloud.rain.fill"),
    ("sunShowers", "cloud.sun.rain.fill"),
    ("scatteredThunderstorms", "cloud.bolt.fill"),
    ("strongStorms", "cloud.bolt.rain.fill"),
    ("thunderstorms", "cloud.bolt.fill"),
    ("frigid", "thermometer.snowflake"),
    ("hail", "cloud.hail.fill"),
    ("hot", "sun.max.fill"),
    ("flurries", "cloud.snow.fill"),
    ("sleet", "cloud.sleet.fill"),
    ("snow", "cloud.snow.fill"),
    ("sunFlurries", "sun.snow.fill"),
    ("wintryMix", "cloud.sleet.fill"),
    ("blizzard", "wind.snow"),
    ("blowingSnow", "wind.snow"),
    ("freezingDrizzle", "cloud.drizzle.fill"),
    ("freezingRain", "cloud.rain.fill"),
    ("heavySnow", "cloud.snow.fill"),
    ("hurricane", "hurricane"),
    ("tropicalStorm", "tropicalstorm"),
];
const TWILIGHT: [&str; 8] = ["breezy", "clear", "frigid", "hot", "mostlyClear", "partlyCloudy", "sunFlurries", "sunShowers"];

pub(crate) fn style(condition: &str, daylight: bool, twilight: &str) -> (&'static str, &'static str, &'static str) {
    let Some((_, symbol)) = SYMBOLS.iter().find(|(key, _)| *key == condition) else {
        return ("cloud.fill", "#000000", "#000000");
    };
    if !TWILIGHT.contains(&condition) {
        return (symbol, "#7790A6", "#AAAAAA");
    }
    match twilight {
        "sunrise" => return ("sunrise.fill", "#AE88FF", "#FFC1AD"),
        "sunset" => return ("sunset.fill", "#6C77DA", "#FFC896"),
        _ => {}
    }
    if daylight { return (symbol, "#2D9AFF", "#6BDCFF"); }
    let night = match condition {
        "clear" | "mostlyClear" | "hot" => "moon.stars.fill",
        "partlyCloudy" => "cloud.moon.fill",
        _ => symbol,
    };
    (night, "#370C6F", "#575CD4")
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn gradient_matrix() {
        for (condition, _) in SYMBOLS {
            for daylight in [true, false] {
                for twilight in ["", "sunrise", "sunset"] {
                    let (_, first, second) = style(condition, daylight, twilight);
                    let expected = if !TWILIGHT.contains(&condition) { ("#7790A6", "#AAAAAA") }
                    else if twilight == "sunrise" { ("#AE88FF", "#FFC1AD") }
                    else if twilight == "sunset" { ("#6C77DA", "#FFC896") }
                    else if daylight { ("#2D9AFF", "#6BDCFF") } else { ("#370C6F", "#575CD4") };
                    assert_eq!((first, second), expected);
                }
            }
        }
    }
    #[test]
    fn icon_examples() {
        assert_eq!(style("hail", true, "").0, "cloud.hail.fill");
        assert_eq!(style("blizzard", true, "sunrise").0, "wind.snow");
        assert_eq!(style("clear", false, "").0, "moon.stars.fill");
        assert_eq!(style("partlyCloudy", false, "").0, "cloud.moon.fill");
        assert_eq!(style("rain", false, "sunrise").0, "cloud.rain.fill");
        assert_eq!(style("breezy", true, "sunrise").0, "sunrise.fill");
        assert_eq!(style("unknown", false, "").1, "#000000");
    }
}
