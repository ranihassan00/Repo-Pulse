package com.repopulse.api;
import com.repopulse.hotspots.HotspotService;
import org.springframework.web.bind.annotation.*;
@RestController
public class HotspotController {
    private final HotspotService hotspots;
    public HotspotController(HotspotService hotspots){this.hotspots=hotspots;}
    @GetMapping("/api/repositories/{id}/hotspots") public HotspotService.HotspotResponse get(@PathVariable long id){return hotspots.forRepository(id);}
}
