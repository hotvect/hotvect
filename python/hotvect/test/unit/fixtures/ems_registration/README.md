# EMS registration responses

These JSON fixtures are recorded registration responses from an EMS release that serves the registration contract:

- `algorithm-get-without-parameter`: algorithm lookup for an algorithm without a parameter.
- `algorithm-get-with-parameter`: algorithm lookup for an algorithm with a parameter.
- `algorithm-parameters-create`: algorithm parameter creation.

Only JSON whitespace was normalized. The training registration test replays both GET cases and the saved POST
response through the generated client and Requests' HTTP request encoding, without accessing EMS or S3.
