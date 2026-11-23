# RegionTick 1.0.0
Paper 1.21.11 plugin source project. Build with Java 21 and Maven:

```bash
mvn clean package
```

The jar will be in `target/RegionTick-1.0.0.jar`.

## Commands
- `/region tick` - status
- `/region tick all` - enable master + utility adjustments
- `/region tick utilities` - toggle utility adjustments
- `/region tick set <action> <ticks>` - set additional per-action reduction
- `/region tick ping` - show ping tiers
- `/region tick tps` - show TPS and compensation settings
- `/region tick reload` - reload config

Permission: `regiontick.admin` (default OP).

## Important technical limits
Paper's active-item API can shorten the server-side use timer for food, drink, bows, crossbows and tridents. This first version applies each reduction once when a use starts. Shortening bow draw time can reduce arrow power because vanilla calculates power from draw duration.

Fishing rod casting has no charge-up duration. Shield raising is effectively immediate/client-driven, so there is no meaningful raise duration for this API to shorten. A plugin cannot make packets travel faster or guarantee earlier hit registration; ping compensation changes selected action timings, not the network path. The `shield` option is reserved and disabled by default.

The `all` command enables the master and utility switches; it does not claim to change mechanics that Paper cannot control.
