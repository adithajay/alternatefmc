/**
 * This file is part of alf.io.
 *
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package alfio.controller.api.admin;

import alfio.manager.AdditionalServiceManager;
import alfio.manager.EventManager;
import alfio.model.AdditionalService;
import alfio.model.Event;
import alfio.model.PriceContainer;
import alfio.model.modification.EventModification;
import alfio.model.result.ValidationResult;
import alfio.repository.EventRepository;
import alfio.util.ExportUtils;
import alfio.util.MonetaryUtil;
import alfio.util.Validator;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNullElse;

@RestController
@RequestMapping("/admin/api")
public class AdditionalServiceApiController {

    private static final Logger log = LoggerFactory.getLogger(AdditionalServiceApiController.class);

    private final EventManager eventManager;
    private final EventRepository eventRepository;
    private final AdditionalServiceManager additionalServiceManager;

    public AdditionalServiceApiController(EventManager eventManager,
                                          EventRepository eventRepository,
                                          AdditionalServiceManager additionalServiceManager) {
        this.eventManager = eventManager;
        this.eventRepository = eventRepository;
        this.additionalServiceManager = additionalServiceManager;
    }


    @ExceptionHandler({IllegalArgumentException.class})
    public ResponseEntity<String> handleBadRequest(Exception e) {
        log.warn("bad input detected", e);
        return new ResponseEntity<>("bad input parameters", HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler({Exception.class})
    public ResponseEntity<String> handleError(Exception e) {
        log.error("error", e);
        return new ResponseEntity<>("internal server error", HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @GetMapping("/event/{eventId}/additional-services")
    public List<EventModification.AdditionalService> loadAll(@PathVariable("eventId") int eventId) {
        return eventRepository.findOptionalById(eventId)
            .map(event -> additionalServiceManager.loadAllForEvent(eventId)
                            .stream()
                            .map(as -> EventModification.AdditionalService.from(as)//.withAdditionalFields() TODO to be implemented
                                            .withText(additionalServiceManager.findAllTextByAdditionalServiceId(as.getId()))
                                            .withZoneId(event.getZoneId())
                                            .withPriceContainer(buildPriceContainer(event, as)).build())
                            .collect(Collectors.toList()))
            .orElse(Collections.emptyList());
    }

    @GetMapping("/event/{eventId}/additional-services/count")
    public Map<Integer, Integer> countUse(@PathVariable("eventId") int eventId) {
        return additionalServiceManager.countUsageForEvent(eventId);
    }

    @PutMapping("/event/{eventId}/additional-services/{additionalServiceId}")
    @Transactional
    public ResponseEntity<EventModification.AdditionalService> update(@PathVariable("eventId") int eventId, @PathVariable("additionalServiceId") int additionalServiceId, @RequestBody EventModification.AdditionalService additionalService, BindingResult bindingResult) {
        ValidationResult validationResult = Validator.validateAdditionalService(additionalService, bindingResult);
        Validate.isTrue(validationResult.isSuccess(), "validation failed");
        Validate.isTrue(additionalServiceId == additionalService.getId(), "wrong input");
        return eventRepository.findOptionalById(eventId)
            .map(event -> {
                int result = additionalServiceManager.update(additionalServiceId, additionalService.isFixPrice(),
                    additionalService.getOrdinal(), additionalService.getAvailableQuantity(), additionalService.getMaxQtyPerOrder(), additionalService.getInception().toZonedDateTime(event.getZoneId()),
                    additionalService.getExpiration().toZonedDateTime(event.getZoneId()), additionalService.getVat(), additionalService.getVatType(), Optional.ofNullable(additionalService.getPrice()).map(p -> MonetaryUtil.unitToCents(p, event.getCurrency())).orElse(0));
                Validate.isTrue(result <= 1, "too many records updated");
                Stream.concat(additionalService.getTitle().stream(), additionalService.getDescription().stream()).
                    forEach(t -> {
                        if(t.getId() != null) {
                            additionalServiceManager.updateText(t.getId(), t.getLocale(), t.getType(), t.getValue());
                        } else {
                            additionalServiceManager.insertText(additionalService.getId(), t.getLocale(), t.getType(), t.getValue());
                        }
                    });
                return ResponseEntity.ok(additionalService);
            }).orElseThrow(IllegalArgumentException::new);
    }

    @PostMapping(value = "/event/{eventId}/additional-services")
    @Transactional
    public ResponseEntity<EventModification.AdditionalService> insert(@PathVariable("eventId") int eventId, @RequestBody EventModification.AdditionalService additionalService, BindingResult bindingResult) {
        ValidationResult validationResult = Validator.validateAdditionalService(additionalService, bindingResult);
        Validate.isTrue(validationResult.isSuccess(), "validation failed");
        return eventRepository.findOptionalById(eventId)
            .map(event -> ResponseEntity.ok(eventManager.insertAdditionalService(event, additionalService)))
            .orElseThrow(IllegalArgumentException::new);
    }

    @DeleteMapping("/event/{eventId}/additional-services/{additionalServiceId}")
    @Transactional
    public ResponseEntity<String> remove(@PathVariable("eventId") int eventId, @PathVariable("additionalServiceId") int additionalServiceId, Principal principal) {
        return eventRepository.findOptionalById(eventId)
            .map(event -> additionalServiceManager.getOptionalById(additionalServiceId, eventId)
                .map(as -> {
                    log.debug("{} is deleting additional service #{}", principal.getName(), additionalServiceId);
                    int deletedTexts = additionalServiceManager.deleteAdditionalServiceTexts(additionalServiceId);
                    log.debug("deleted {} texts", deletedTexts);
                    //TODO add configuration fields and values
                    additionalServiceManager.delete(additionalServiceId, eventId);
                    log.debug("additional service #{} successfully deleted", additionalServiceId);
                    return ResponseEntity.ok("OK");
                })
                .orElseGet(() -> new ResponseEntity<>("additional service not found", HttpStatus.NOT_FOUND)))
            .orElseGet(() -> new ResponseEntity<>("event not found", HttpStatus.NOT_FOUND));
    }

    @GetMapping("/events/{eventName}/additional-services/{type}/export")
    public void exportAdditionalServices(@PathVariable("eventName") String eventName,
                                         @PathVariable("type") AdditionalService.AdditionalServiceType additionalServiceType,
                                         HttpServletResponse response,
                                         Principal principal) throws IOException {
        var event = eventManager.getOptionalByName(eventName, principal.getName()).orElseThrow();
        var formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        var header = List.of(
            "ID",
            "Name",
            "Creation",
            "Last Update",
            "Reservation ID",
            "Reservation First name",
            "Reservation Last name",
            "Reservation Email address",
            "Paid Amount",
            "Currency",
            "VAT",
            "Discount"
        );
        var locale = event.getContentLanguages().get(0).getLanguage();
        var rows = additionalServiceManager.exportItemsForEvent(additionalServiceType, event.getId(), locale).stream()
            .map(item -> new String[] {
                item.getUuid(),
                item.getAdditionalServiceTitle(),
                item.getUtcCreation().withZoneSameInstant(event.getZoneId()).format(formatter),
                requireNonNullElse(item.getUtcLastModified(), item.getUtcCreation()).withZoneSameInstant(event.getZoneId()).format(formatter),
                item.getTicketsReservationUuid(),
                item.getFirstName(),
                item.getLastName(),
                item.getEmailAddress(),
                MonetaryUtil.formatCents(item.getFinalPriceCts(), item.getCurrencyCode()),
                item.getCurrencyCode(),
                MonetaryUtil.formatCents(item.getVatCts(), item.getCurrencyCode()),
                MonetaryUtil.formatCents(item.getDiscountCts(), item.getCurrencyCode())
            });
        ExportUtils.exportExcel(event.getShortName() + "-" + additionalServiceType.name() + ".xlsx",
            additionalServiceType.name(),
            header.toArray(String[]::new),
            rows,
            response);
    }

    @PostMapping("/additional-services/validate")
    public ValidationResult checkAdditionalService(@RequestBody EventModification.AdditionalService additionalService, BindingResult bindingResult) {
        return Validator.validateAdditionalService(additionalService, bindingResult);
    }

    private static PriceContainer buildPriceContainer(final Event event, final AdditionalService as) {
        return new PriceContainer() {
            @Override
            public int getSrcPriceCts() {
                return as.isFixPrice() ? as.getSrcPriceCts() : 0;
            }

            @Override
            public String getCurrencyCode() {
                return event.getCurrency();
            }

            @Override
            public Optional<BigDecimal> getOptionalVatPercentage() {
                return getVatStatus() == VatStatus.NONE ? Optional.empty() : Optional.of(event.getVat());
            }

            @Override
            public VatStatus getVatStatus() {
                return AdditionalService.getVatStatus(as.getVatType(), event.getVatStatus());
            }
        };
    }
}
