# Play Console forms: Data safety, content rating, app access

Answers from what the app and server actually do (same facts as backend/privacy.py). Keep them
in step with the privacy policy.

## Data safety

**Does your app collect or share any of the required user data types?** Yes.
**Is all of the user data collected by your app encrypted in transit?** Yes (HTTPS only; plain
HTTP is allowed only to the emulator's 10.0.2.2 in test builds).
**Do you provide a way for users to request that their data is deleted?** Yes - in the app
(Settings -> My account -> Delete account), and by email.
**Account deletion URL** (asked separately): the privacy policy URL, section 7.

| Data type | Collected | Shared | Optional? | Purpose | Notes |
|---|---|---|---|---|---|
| Personal info -> Email address | Yes | No | Required | Account management | login |
| Personal info -> Name (nickname) | Yes | No | Required | Account management | nickname shown in the app |
| Location -> Approximate & precise | Yes* | No | Required for features | App functionality | *Sent only to plan courses/routes and search places; not stored with the account. Workout tracks stay on the phone. |
| App activity -> Other user-generated content (search words) | Yes* | No | Optional | App functionality | course requests, place search; cached without the account |
| Health and fitness -> Fitness info | No | No | - | - | workouts, steps stay on the phone (on-device processing isn't "collection") |
| Photos and videos | No | No | - | - | shown from the gallery, never uploaded |
| App info and performance -> Crash logs / diagnostics | No | No | - | - | no analytics or crash SDK |
| Device or other IDs | No | No | - | - | |

"Shared": routes go to TMAP and maps to NAVER as service providers acting for the app; Play counts
service providers as not sharing.

Ephemeral processing: location for course planning is processed and cached by place, not tied
to a user -> may also be declared "processed ephemerally" where offered.

## Content rating (IARC questionnaire)
- Category: Reference, News, or Educational? No -> "All other app types" / Utility, Productivity...
- Violence, sexuality, language, controlled substances, gambling: none.
- User interaction: no user-to-user communication or sharing between users; no user-generated
  content visible to others.
- Shares the user's location with other users: No.
- Digital purchases: No.
Expected: 전체이용가 / Everyone (PEGI 3).

## Target audience
13+ is not enough in Korea's terms - choose 18 and over (or 14+ where offered); not designed for
children. No ads.

## App access (for reviewers)
Login is required. Give the reviewers a test account made for review (email + password), or
note that Google sign-in works with any Google account. Location: works anywhere, courses are
Korea-only (outside Korea the course features say there's nothing nearby).

## Ads
No ads.

## Health apps declaration
Fitness tracking (steps, workouts) - not a medical device. Health Connect: writes exercise
sessions and routes, reads steps; the Health Connect permissions declaration needs the
privacy policy URL and a short reason per permission:
- WRITE_EXERCISE, WRITE_EXERCISE_ROUTE, WRITE_DISTANCE, WRITE_TOTAL_CALORIES_BURNED: save the
  workouts the user records (session, route, distance, calories)
- READ_STEPS: show the day's total steps on the home and records screens

## Permission declarations Play asks for (review risks)
- **Foreground service, type location** (FOREGROUND_SERVICE_LOCATION): declare "the user starts
  a workout and the app records the route with the screen off"; Play asks for a short video of
  starting a workout and the ongoing notification. Expected to pass: it's the core feature.
- **Photos**: no gallery permission (removed 2026-10-18 for Play's photo policy); workout photos
  come from the system photo picker, which needs none. Nothing to declare.
- Activity recognition, calendar, boot completed, notifications: no special declaration beyond
  the Data safety form.
