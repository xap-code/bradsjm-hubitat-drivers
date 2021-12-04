/**
 *  MIT License
 *  Copyright 2020 Jonathan Bradshaw (jb@nrgup.net)
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
*/

import com.hubitat.app.exception.UnknownDeviceTypeException
import com.hubitat.app.ChildDeviceWrapper
import com.hubitat.app.DeviceWrapper
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.spec.SecretKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import hubitat.helper.HexUtils
import hubitat.scheduling.AsyncResponse

/*
 *  Changelog:
 *  10/06/21 - 0.1   - Initial release
 *  10/08/21 - 0.1.1 - added Scene Switch TS004F productKey:xabckq1v
 *  10/09/21 - 0.1.2 - added Scene Switch TS0044 productKey:vp6clf9d; added battery reports (when the virtual driver supports it)
 *  10/10/21 - 0.1.3 - brightness, temperature, humidity, CO2 sensors
 *  10/11/21 - 0.1.4 - door contact, water, smoke, co, pir sensors, fan
 *  10/13/21 - 0.1.5 - fix ternary use error for colors and levels
 *  10/14/21 - 0.1.6 - smart plug, vibration sensor; brightness and temperature sensors scaling bug fix
 *  10/17/21 - 0.1.7 - switched API to use device specification request to get both functions and status ranges
 *  10/25/21 - 0.1.8 - Added support for Window Shade using a custom component driver
 *  10/26/21 - 0.1.9 - Add support for heating devices with custom component driver
 *  10/27/21 - 0.2.0 - Created country to datacenter map (https://developer.tuya.com/en/docs/iot/oem-app-data-center-distributed?id=Kafi0ku9l07qb)
 *
 *  Custom component drivers located at https://github.com/bradsjm/hubitat-drivers/tree/master/Component
 */

metadata {
    definition (name: 'Tuya IoT Platform (Cloud)', namespace: 'tuya', author: 'Jonathan Bradshaw') {
        capability 'Initialize'
        capability 'Refresh'

        command 'removeDevices'

        attribute 'deviceCount', 'number'
        attribute 'state', 'enum', [
            'not configured',
            'error',
            'authenticating',
            'authenticated',
            'connected',
            'disconnected',
            'ready'
        ]
    }

    preferences {
        section {
            input name: 'access_id',
                  type: 'text',
                  title: 'Tuya API Access/Client Id',
                  required: true

            input name: 'access_key',
                  type: 'password',
                  title: 'Tuya API Access/Client Secret',
                  required: true

            input name: 'appSchema',
                  title: 'Tuya Application',
                  type: 'enum',
                  required: true,
                  defaultValue: 'tuyaSmart',
                  options: [
                    'tuyaSmart': 'Tuya Smart Life App',
                    'smartlife': 'Smart Life App'
                ]

            input name: 'username',
                  type: 'text',
                  title: 'Tuya Application Login',
                  required: true

            input name: 'password',
                  type: 'password',
                  title: 'Tuya Application Password',
                  required: true

            input name: 'appCountry',
                  title: 'Tuya Application Country',
                  type: 'enum',
                  required: true,
                  defaultValue: 'United States',
                  options: tuyaCountries.country

            input name: 'logEnable',
                  type: 'bool',
                  title: 'Enable debug logging',
                  required: false,
                  defaultValue: true

            input name: 'txtEnable',
                  type: 'bool',
                  title: 'Enable descriptionText logging',
                  required: false,
                  defaultValue: true
        }
    }
}

// Tuya Function Categories
@Field static final Map<String, List<String>> tuyaFunctions = [
    'battery'        : [ 'battery_percentage', 'va_battery' ],
    'brightness'     : [ 'bright_value', 'bright_value_v2', 'bright_value_1' ],
    'co'             : [ 'co_state' ],
    'co2'            : [ 'co2_value' ],
    'colour'         : [ 'colour_data', 'colour_data_v2' ],
    'contact'        : [ 'doorcontact_state' ],
    'ct'             : [ 'temp_value', 'temp_value_v2' ],
    'control'        : [ 'control' ],
    'fanSpeed'       : [ 'fan_speed' ],
    'light'          : [ 'switch_led', 'switch_led_1', 'light' ],
    'meteringSwitch' : [ 'countdown_1' , 'add_ele' , 'cur_current', 'cur_power', 'cur_voltage' , 'relay_status', 'light_mode' ],
    'omniSensor'     : [ 'bright_value', 'humidity_value', 'va_humidity', 'bright_sensitivity', 'shock_state', 'inactive_state', 'sensitivity' ],
    'pir'            : [ 'pir' ],
    'power'          : [ 'Power', 'power', 'switch', 'switch_1', 'switch_2', 'switch_3', 'switch_4', 'switch_5', 'switch_6', 'switch_usb1', 'switch_usb2', 'switch_usb3', 'switch_usb4', 'switch_usb5', 'switch_usb6' ],
    'percentControl' : [ 'percent_control' ],
    'sceneSwitch'    : [ 'switch1_value', 'switch2_value', 'switch3_value', 'switch4_value', 'switch_mode2', 'switch_mode3', 'switch_mode4' ],
    'smoke'          : [ 'smoke_sensor_status' ],
    'temperatureSet' : [ 'temp_set' ],
    'temperature'    : [ 'temp_current', 'va_temperature' ],
    'water'          : [ 'watersensor_state' ],
    'workMode'       : [ 'work_mode' ],
    'workState'      : [ 'work_state' ]
]

// Tuya -> Hubitat attributes mappings
// TS004F  productKey:xabckq1v        TS0044 productKey:vp6clf9d
@Field static final Map<String, String> sceneSwitchAction = [
    'single_click'  : 'pushed',             // TS004F
    'double_click'  : 'doubleTapped',
    'long_press'    : 'held',
    'click'         : 'pushed',             // TS0044
    'double_click'  : 'doubleTapped',
    'press'         : 'held'
]
@Field static final Map<String, String> sceneSwitchKeyNumbers = [
    'switch_mode2'  : '2',                // TS0044
    'switch_mode3'  : '3',
    'switch_mode4'  : '4',
    'switch1_value' : '4',                // '4'for TS004F and '1' for TS0044 !
    'switch2_value' : '3',                // TS004F - match the key numbering as in Hubitat built-in TS0044 driver
    'switch3_value' : '1',
    'switch4_value' : '2',
]

// Constants
@Field static final Integer maxMireds = 500 // 2000K
@Field static final Integer minMireds = 153 // 6536K

// Jason Parsing Cache
@Field static final ConcurrentHashMap<String, Map> jsonCache = new ConcurrentHashMap<>()

// Track for dimming operations
@Field static final ConcurrentHashMap<String, Integer> levelChanges = new ConcurrentHashMap<>()

// Random number generator
@Field static final Random random = new Random()

/*
 * Tuya default attributes used if missing from device details
 */
@Field static final Map defaults = [
    'battery_percentage': [ min: 0, max: 100, scale: 0, step: 1, unit: '%', type: 'Integer' ],
    'bright_value': [ min: 0, max: 100, scale: 0, step: 1, type: 'Integer' ],
    'bright_value_v2': [ min: 0, max: 100, scale: 0, step: 1, type: 'Integer' ],
    'co2': [ min: 0, max: 1000, scale: 1, step: 1, type: 'Integer' ],
    'fanSpeed': [ min: 1, max: 100, scale: 0, step: 1, type: 'Integer' ],
    'fanSpeedPercent': [ min: 1, max: 100, scale: 0, step: 1, type: 'Integer' ],
    'temp_value': [ min: 0, max: 100, scale: 0, step: 1, type: 'Integer' ],
    'temp_value_v2': [ min: 0, max: 100, scale: 0, step: 1, type: 'Integer' ],
    'colour_data': [
        h: [ min: 1, scale: 0, max: 360, step: 1, type: 'Integer' ],
        s: [ min: 1, scale: 0, max: 255, step: 1, type: 'Integer' ],
        v: [ min: 1, scale: 0, max: 255, step: 1, type: 'Integer' ]
    ],
    'colour_data_v2': [
        h: [ min: 1, scale: 0, max: 360, step: 1, type: 'Integer' ],
        s: [ min: 1, scale: 0, max: 1000, step: 1, type: 'Integer' ],
        v: [ min: 1, scale: 0, max: 1000, step: 1, type: 'Integer' ]
    ],
    'humidity_value': [ min: 0, max: 100, scale: 0, step: 1, type: 'Integer' ],
    'temp_current': [ min: -400, max: 2000, scale: 1, step: 1, unit: '°C', type: 'Integer' ],
    'va_humidity': [ min: 0, max: 1000, scale: 1, step: 1, type: 'Integer' ],
    'va_temperature': [ min: 0, max: 1000, scale: 1, step: 1, type: 'Integer' ]
]

/**
 *  Hubitat Driver Event Handlers
 */
// Component command to cycle fan speed
void componentCycleSpeed(DeviceWrapper dw) {
    switch (dw.currentValue('speed')) {
        case 'low':
        case 'medium-low':
            componentSetSpeed(dw, 'medium')
            break
        case 'medium':
        case 'medium-high':
            componentSetSpeed(dw, 'high')
            break
        case 'high':
            componentSetSpeed(dw, 'low')
            break
    }
}

// Component command to close device
void componentClose(DeviceWrapper dw) {
    Map<String, Map> functions = getFunctions(dw)
    String code = getFunctionCode(functions, tuyaFunctions.control)

    if (code) {
        log.info "Closing ${dw}"
        tuyaSendDeviceCommandsAsync(dw.getDataValue('id'), [ 'code': code, 'value': 'close' ])
    }
}

// Component command to turn on device
void componentOn(DeviceWrapper dw) {
    Map<String, Map> functions = getFunctions(dw)
    String code = getFunctionCode(functions, tuyaFunctions.light + tuyaFunctions.power)

    if (code) {
        log.info "Turning ${dw} on"
        tuyaSendDeviceCommandsAsync(dw.getDataValue('id'), [ 'code': code, 'value': true ])
    }
}

// Component command to turn off device
void componentOff(DeviceWrapper dw) {
    Map<String, Map> functions = getFunctions(dw)
    String code = getFunctionCode(functions, tuyaFunctions.light + tuyaFunctions.power)

    if (code) {
        log.info "Turning ${dw} off"
        tuyaSendDeviceCommandsAsync(dw.getDataValue('id'), [ 'code': code, 'value': false ])
    }
}

// Component command to open device
void componentOpen(DeviceWrapper dw) {
    Map<String, Map> functions = getFunctions(dw)
    String code = getFunctionCode(functions, tuyaFunctions.control)

    if (code) {
        log.info "Opening ${dw}"
        tuyaSendDeviceCommandsAsync(dw.getDataValue('id'), [ 'code': code, 'value': 'open' ])
    }
}

// Component command to refresh device
void componentRefresh(DeviceWrapper dw) {
    String id = dw.getDataValue('id')
    if (id && dw.getDataValue('functions')) {
        log.info "Refreshing ${dw} (${id})"
        tuyaGetStateAsync(id)
    }
}

// Component command to set color
void componentSetColor(DeviceWrapper dw, Map colorMap) {
    Map<String, Map> functions = getFunctions(dw)
    String code = getFunctionCode(functions, tuyaFunctions.colour)
    if (code) {
        Map color = functions[code] ?: defaults[code]
        // An oddity and workaround for mapping brightness values
        Map bright = getFunction(functions, functions.brightness) ?: color.v
        Map value = [
            h: remap(colorMap.hue, 0, 100, color.h.min, color.h.max),
            s: remap(colorMap.saturation, 0, 100, color.s.min, color.s.max),
            v: remap(colorMap.level, 0, 100, bright.min, bright.max)
        ]
        log.info "Setting ${dw} color to ${colorMap}"
        tuyaSendDeviceCommandsAsync(dw.getDataValue('id'),
            [ 'code': code, 'value': value ],
            [ 'code': 'work_mode', 'value': 'colour']
        )
    }
}

// Component command to set color temperature
void componentSetColorTemperature(DeviceWrapper dw, BigDecimal kelvin,
                                  BigDecimal level = null, BigDecimal duration = null) {
    Map<String, Map> functions = getFunctions(dw) << getStatusSet(dw)
    String code = getFunctionCode(functions, tuyaFunctions.ct)
    if (code) {
        Map temp = functions[code] ?: defaults[code]
        Integer value = temp.max - Math.ceil(remap(1000000 / kelvin, minMireds, maxMireds, temp.min, temp.max))
        log.info "Setting ${dw} color temperature to ${kelvin}K"
        tuyaSendDeviceCommandsAsync(dw.getDataValue('id'),
            [ 'code': code, 'value': value ],
            [ 'code': 'work_mode', 'value': 'white']
        )
    }
    if (level && dw.currentValue('level') != level) {
        componentSetLevel(dw, level, duration)
    }
}

// Component command to set effect
void componentSetEffect(DeviceWrapper dw, BigDecimal index) {
    log.warn "${device} Set effect command not supported"
}

// Component command to set heating setpoint
void componentSetHeatingSetpoint(DeviceWrapper dw, BigDecimal temperature) {
    Map<String, Map> functions = getFunctions(dw)
    String code = getFunctionCode(functions, tuyaFunctions.temperatureSet)
    if (code) {
        log.info "Setting ${dw} heating set point to ${temperature}"
        tuyaSendDeviceCommandsAsync(dw.getDataValue('id'), [ 'code': code, 'value': temperature ])
    }
}

// Component command to set hue
void componentSetHue(DeviceWrapper dw, BigDecimal hue) {
    componentSetColor(dw, [
        hue: hue,
        saturation: dw.currentValue('saturation'),
        level: dw.currentValue('level')
    ])
}

// Component command to set level
/* groovylint-disable-next-line UnusedMethodParameter */
void componentSetLevel(DeviceWrapper dw, BigDecimal level, BigDecimal duration = 0) {
    String colorMode = dw.currentValue('colorMode') ?: 'CT'
    if (colorMode == 'CT') {
        Map<String, Map> functions = getFunctions(dw)
        String code = getFunctionCode(functions, tuyaFunctions.brightness)
        if (code) {
            Map bright = functions[code] ?: defaults[code]
            Integer value = Math.ceil(remap(level, 0, 100, bright.min, bright.max))
            log.info "Setting ${dw} level to ${level}%"
            tuyaSendDeviceCommandsAsync(dw.getDataValue('id'), [ 'code': code, 'value': value ])
        }
    } else {
        componentSetColor(dw, [
            hue: dw.currentValue('hue'),
            saturation: dw.currentValue('saturation'),
            level: level
        ])
    }
}

void componentSetNextEffect(DeviceWrapper device) {
    log.warn 'Set next effect command not supported'
}

void componentSetPreviousEffect(DeviceWrapper device) {
    log.warn 'Set previous effect command not supported'
}

// Component command to set position
void componentSetPosition(DeviceWrapper dw, BigDecimal position) {
    Map<String, Map> functions = getFunctions(dw)
    String code = getFunctionCode(functions, tuyaFunctions.percentControl)

    if (code) {
        log.info "Setting ${dw} position to ${position}"
        tuyaSendDeviceCommandsAsync(dw.getDataValue('id'), [ 'code': code, 'value': position as Integer ])
    }
}

// Component command to set saturation
void componentSetSaturation(DeviceWrapper dw, BigDecimal saturation) {
    componentSetColor(dw, [
        hue: dw.currentValue('hue'),
        saturation: saturation,
        level: dw.currentValue('level')
    ])
}

// Component command to set fan speed
void componentSetSpeed(DeviceWrapper dw, String speed) {
    Map<String, Map> functions = getFunctions(dw)
    String code = getFunctionCode(functions, tuyaFunctions.fanSpeed)
    String id = dw.getDataValue('id')
    if (code) {
        log.info "Setting speed to ${speed}"
        switch (speed) {
            case 'on':
                tuyaSendDeviceCommandsAsync(id, [ 'code': 'switch', 'value': true ])
                break
            case 'off':
                tuyaSendDeviceCommandsAsync(id, [ 'code': 'switch', 'value': false ])
                break
            case 'auto':
                log.warn 'Speed level auto is not supported'
                break
            default:
                Map speedFunc = functions[code] ?: defaults[code]
                int speedVal = ['low', 'medium-low', 'medium', 'medium-high', 'high'].indexOf(speed)
                String value
                switch (speedFunc.type) {
                    case 'Enum':
                        value = speedFunc.range[remap(speedVal, 0, 4, 0, speedFunc.range.size() - 1) as int]
                        break
                    case 'Integer':
                        value = remap(speedVal, 0, 4, speedFunc.min as int ?: 1, speedFunc.max as int ?: 100)
                        break
                    default:
                        log.warn "Unknown fan speed function type ${speedFunc}"
                        return
                }
                tuyaSendDeviceCommandsAsync(id, [ 'code': code, 'value': value ])
                break
        }
    }
}

// Component command to start level change (up or down)
void componentStartLevelChange(DeviceWrapper dw, String direction) {
    levelChanges[dw.deviceNetworkId] = (direction == 'down') ? -10 : 10
    log.info "Starting level change ${direction} for ${dw}"
    runInMillis(1000, 'doLevelChange')
}

// Component command to stop level change
void componentStopLevelChange(DeviceWrapper dw) {
    log.info "Stopping level change for ${dw}"
    levelChanges.remove(dw.deviceNetworkId)
}

// Component command to set position direction
void componentStartPositionChange(DeviceWrapper dw, String direction) {
    switch (direction) {
        case 'open': componentOpen(dw); break
        case 'close': componentClose(dw); break
        default:
            log.warn "${device} Unknown position change direction ${direction} for ${dw}"
            break
    }
}

// Component command to stop position change
void componentStopPositionChange(DeviceWrapper dw) {
    Map<String, Map> functions = getFunctions(dw)
    String code = getFunctionCode(functions, tuyaFunctions.control)

    if (code) {
        log.info "Stopping ${dw}"
        tuyaSendDeviceCommandsAsync(dw.getDataValue('id'), [ 'code': code, 'value': 'stop' ])
    }
}

// Utility function to handle multiple level changes
void doLevelChange() {
    List active = levelChanges.collect() // copy list locally
    active.each { kv ->
        ChildDeviceWrapper dw = getChildDevice(kv.key)
        if (dw) {
            int newLevel = (dw.currentValue('level') as int) + kv.value
            if (newLevel < 0) { newLevel = 0 }
            if (newLevel > 100) { newLevel = 100 }
            componentSetLevel(dw, newLevel)
            if (newLevel <= 0 && newLevel >= 100) {
                componentStopLevelChange(device)
            }
        } else {
            levelChanges.remove(kv.key)
        }
    }

    if (!levelChanges.isEmpty()) {
        runInMillis(1000, 'doLevelChange')
    }
}

// Called when the device is started
void initialize() {
    log.info "${device} driver initializing"
    state.clear()
    unschedule()

    sendEvent([ name: 'deviceCount', value: 0 ])
    Map datacenter = tuyaCountries.find { c -> c.country == settings.appCountry }
    if (datacenter) {
        state.endPoint = datacenter.endpoint
        state.countryCode = datacenter.countryCode
    } else {
        log.error "${device} Country not set in configuration"
        sendEvent([ name: 'state', value: 'error', descriptionText: 'Country not set in configuration'])
    }

    state.with {
        tokenInfo = [ access_token: '', expire: now() ] // initialize token
        uuid = state?.uuid ?: UUID.randomUUID().toString()
        driver_version = '0.2'
        lang = 'en'
    }

    tuyaAuthenticateAsync()
}

// Called when the device is first created
void installed() {
    log.info "${device} driver installed"
}

// Called when the device is removed
void uninstalled() {
    log.info "${device} driver uninstalled"
}

// Called when the settings are updated
void updated() {
    log.info "${device} driver configuration updated"
    if (logEnable) {
        log.debug settings
        runIn(1800, 'logsOff')
    }

    initialize()
}

// Called to parse received MQTT data
void parse(String data) {
    Map payload = new JsonSlurper().parseText(interfaces.mqtt.parseMessage(data).payload)
    Cipher cipher = Cipher.getInstance('AES/ECB/PKCS5Padding')
    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(state.mqttInfo.password[8..23].bytes, 'AES'))
    Map result = new JsonSlurper().parse(cipher.doFinal(payload.data.decodeBase64()), 'UTF-8')

    if (result.status && (result.id || result.devId)) {
        updateMultiDeviceStatus(result)
    } else if (result.bizCode && result.bizData) {
        parseBizData(result.bizCode, result.bizData)
    } else {
        log.warn "${device} unsupported mqtt packet: ${result}"
    }
}

// Called to parse MQTT client status changes
void mqttClientStatus(String status) {
    switch (status) {
        case 'Status: Connection succeeded':
            sendEvent([ name: 'state', value: 'connected', descriptionText: 'Connected to Tuya MQTT hub'])
            runInMillis(1000, 'tuyaHubSubscribeAsync')
            break
        default:
            log.error "${device} MQTT connection error: " + status
            sendEvent([ name: 'state', value: 'disconnected', descriptionText: 'Disconnected from Tuya MQTT hub'])
            runIn(15 + random.nextInt(45), 'tuyaGetHubConfigAsync')
            break
    }
}

// Command to refresh all devices
void refresh() {
    log.info "${device} refreshing devices"
    tuyaGetDevicesAsync()
}

// Command to remove all the child devices
void removeDevices() {
    log.info "${device} removing all child devices"
    childDevices.each { device -> deleteChildDevice(device.deviceNetworkId) }
}

// define country map
private static Map country(String country, String countryCode, String endpoint = 'https://openapi.tuyaus.com') {
    return [ country: country, countryCode: countryCode, endpoint: endpoint ]
}

/**
  *  Tuya Standard Instruction Set Category Mapping to Hubitat Drivers
  *  https://developer.tuya.com/en/docs/iot/standarddescription?id=K9i5ql6waswzq
  */
private static Map mapTuyaCategory(Map d) {
    switch (d.category) {
        // Lighting
        case 'dc':    // String Lights
        case 'dd':    // Strip Lights
        case 'dj':    // Light
        case 'tgq':   // Dimmer Light
        case 'tyndj': // Solar Light
        case 'xdd':   // Ceiling Light
        case 'ykq':   // Remote Control
            if (getFunctionCode(d.statusSet, tuyaFunctions.colour)) {
                return [ namespace: 'hubitat', name: 'Generic Component RGBW' ]
            } else if (getFunctionCode(d.statusSet, tuyaFunctions.ct)) {
                return [ namespace: 'hubitat', name: 'Generic Component CT' ]
            } else if (getFunctionCode(d.statusSet, tuyaFunctions.brightness)) {
                return [ namespace: 'hubitat', name: 'Generic Component Dimmer' ]
            }
        case 'fsd ':  // Ceiling Fan (with Light)
            return [ namespace: 'hubitat', name: 'Generic Component Fan Control' ]

        // Electrical
        case 'tgkg':  // Dimmer Switch
            return [ namespace: 'hubitat', name: 'Generic Component Dimmer' ]
        case 'wxkg':  // Scene Switch (TS004F in 'Device trigger' mode only; TS0044)
            return [ namespace: 'hubitat', name: 'Generic Component Central Scene Switch' ]
        case 'cz':    // Smart Plug
            return [ namespace: 'hubitat', name: 'Generic Component Metering Switch' ]
        case 'cl':    // Curtain Motor (uses custom driver)
        case 'clkg':
            return [ namespace: 'component', name: 'Generic Component Window Shade' ]
        case 'pc':    // Power Strip (https://developer.tuya.com/en/docs/iot/s?id=K9gf7o5prgf7s)
            return [
                namespace: 'hubitat',
                name: 'Generic Component Switch',
                devices: [
                    'switch_1': 'Socket 1',
                    'switch_2': 'Socket 2',
                    'switch_3': 'Socket 3',
                    'switch_4': 'Socket 4',
                    'switch_5': 'Socket 5',
                    'switch_6': 'Socket 6',
                    'switch_usb1': 'USB 1',
                    'switch_usb2': 'USB 2',
                    'switch_usb3': 'USB 3',
                    'switch_usb4': 'USB 4',
                    'switch_usb5': 'USB 5',
                    'switch_usb6': 'USB 6'
                ]
            ]

        // Security & Sensors
        case 'ms':    // Lock
            return [ namespace: 'hubitat', name: 'Generic Component Lock' ]
        case 'ldcg':  // Brightness, temperature, humidity, CO2 sensors
        case 'wsdcg':
        case 'zd':    // Vibration sensor as motion
            return [ namespace: 'hubitat', name: 'Generic Component Omni Sensor' ]
        case 'mcs':   // Contact Sensor
            return [ namespace: 'hubitat', name: 'Generic Component Contact Sensor' ]
        case 'sj':    // Water Sensor
            return [ namespace: 'hubitat', name: 'Generic Component Water Sensor' ]
        case 'ywbj':  // Smoke Detector
            return [ namespace: 'hubitat', name: 'Generic Component Smoke Detector' ]
        case 'cobj':  // CO Detector
            return [ namespace: 'hubitat', name: 'Generic Component Carbon Monoxide Detector' ]
        case 'co2bj': // CO2 Sensor
            return [ namespace: 'hubitat', name: 'Generic Component Carbon Dioxide Detector' ]
        case 'pir':   // Motion Sensor
            return [ namespace: 'hubitat', name: 'Generic Component Motion Sensor' ]

        // Large Home Appliances

        // Small Home Appliances
        case 'qn':    // Heater
            return [ namespace: 'component', name: 'Generic Component Heating Device' ]

        // Kitchen Appliances
    }

    return [ namespace: 'hubitat', name: 'Generic Component Switch' ]
}

private static Map<String, Map> getFunctions(DeviceWrapper dw) {
    return jsonCache.computeIfAbsent(dw.getDataValue('functions') ?: '{}') {
        k -> new JsonSlurper().parseText(k)
    }
}

private static Map getFunction(Map functions, List codes) {
    return functions.find({ f -> f.key in codes })?.value
}

private static String getFunctionCode(Map functions, List codes) {
    return codes.find { c -> functions.containsKey(c) }
}

private static Map<String, Map> getStatusSet(DeviceWrapper dw) {
    return jsonCache.computeIfAbsent(dw.getDataValue('statusSet') ?: '{}') {
        k -> new JsonSlurper().parseText(k)
    }
}

private static BigDecimal remap(BigDecimal oldValue, BigDecimal oldMin, BigDecimal oldMax,
                                BigDecimal newMin, BigDecimal newMax) {
    BigDecimal value = oldValue
    if (value < oldMin) { value = oldMin }
    if (value > oldMax) { value = oldMax }
    BigDecimal newValue = ( (value - oldMin) / (oldMax - oldMin) ) * (newMax - newMin) + newMin
    return newValue.setScale(1, BigDecimal.ROUND_HALF_UP)
}

private static String translateColorName(Integer hue, Integer saturation) {
    if (saturation < 1) {
        return 'White'
    }

    switch (hue * 3.6 as int) {
        case 0..15: return 'Red'
        case 16..45: return 'Orange'
        case 46..75: return 'Yellow'
        case 76..105: return 'Chartreuse'
        case 106..135: return 'Green'
        case 136..165: return 'Spring'
        case 166..195: return 'Cyan'
        case 196..225: return 'Azure'
        case 226..255: return 'Blue'
        case 256..285: return 'Violet'
        case 286..315: return 'Magenta'
        case 316..345: return 'Rose'
        case 346..360: return 'Red'
    }

    return ''
}

@Field static final List<Map> tuyaCountries = [
    country('Afghanistan', '93', 'https://openapi.tuyaeu.com'),
    country('Albania', '355', 'https://openapi.tuyaeu.com'),
    country('Algeria', '213', 'https://openapi.tuyaeu.com'),
    country('American Samoa', '1-684', 'https://openapi.tuyaeu.com'),
    country('Andorra', '376', 'https://openapi.tuyaeu.com'),
    country('Angola', '244', 'https://openapi.tuyaeu.com'),
    country('Anguilla', '1-264', 'https://openapi.tuyaeu.com'),
    country('Antarctica', '672', 'https://openapi.tuyaus.com'),
    country('Antigua and Barbuda', '1-268', 'https://openapi.tuyaeu.com'),
    country('Argentina', '54', 'https://openapi.tuyaus.com'),
    country('Armenia', '374', 'https://openapi.tuyaeu.com'),
    country('Aruba', '297', 'https://openapi.tuyaeu.com'),
    country('Australia', '61', 'https://openapi.tuyaeu.com'),
    country('Austria', '43', 'https://openapi.tuyaeu.com'),
    country('Azerbaijan', '994', 'https://openapi.tuyaeu.com'),
    country('Bahamas', '1-242', 'https://openapi.tuyaeu.com'),
    country('Bahrain', '973', 'https://openapi.tuyaeu.com'),
    country('Bangladesh', '880', 'https://openapi.tuyaeu.com'),
    country('Barbados', '1-246', 'https://openapi.tuyaeu.com'),
    country('Belarus', '375', 'https://openapi.tuyaeu.com'),
    country('Belgium', '32', 'https://openapi.tuyaeu.com'),
    country('Belize', '501', 'https://openapi.tuyaeu.com'),
    country('Benin', '229', 'https://openapi.tuyaeu.com'),
    country('Bermuda', '1-441', 'https://openapi.tuyaeu.com'),
    country('Bhutan', '975', 'https://openapi.tuyaeu.com'),
    country('Bolivia', '591', 'https://openapi.tuyaus.com'),
    country('Bosnia and Herzegovina', '387', 'https://openapi.tuyaeu.com'),
    country('Botswana', '267', 'https://openapi.tuyaeu.com'),
    country('Brazil', '55', 'https://openapi.tuyaus.com'),
    country('British Indian Ocean Territory', '246', 'https://openapi.tuyaus.com'),
    country('British Virgin Islands', '1-284', 'https://openapi.tuyaeu.com'),
    country('Brunei', '673', 'https://openapi.tuyaeu.com'),
    country('Bulgaria', '359', 'https://openapi.tuyaeu.com'),
    country('Burkina Faso', '226', 'https://openapi.tuyaeu.com'),
    country('Burundi', '257', 'https://openapi.tuyaeu.com'),
    country('Cambodia', '855', 'https://openapi.tuyaeu.com'),
    country('Cameroon', '237', 'https://openapi.tuyaeu.com'),
    country('Canada', '1', 'https://openapi.tuyaus.com'),
    country('Capo Verde', '238', 'https://openapi.tuyaeu.com'),
    country('Cayman Islands', '1-345', 'https://openapi.tuyaeu.com'),
    country('Central African Republic', '236', 'https://openapi.tuyaeu.com'),
    country('Chad', '235', 'https://openapi.tuyaeu.com'),
    country('Chile', '56', 'https://openapi.tuyaus.com'),
    country('China', '86', 'https://openapi.tuyacn.com'),
    country('Christmas Island', '61'),
    country('Cocos Islands', '61'),
    country('Colombia', '57', 'https://openapi.tuyaus.com'),
    country('Comoros', '269', 'https://openapi.tuyaeu.com'),
    country('Cook Islands', '682', 'https://openapi.tuyaus.com'),
    country('Costa Rica', '506', 'https://openapi.tuyaeu.com'),
    country('Croatia', '385', 'https://openapi.tuyaeu.com'),
    country('Cuba', '53'),
    country('Curacao', '599', 'https://openapi.tuyaus.com'),
    country('Cyprus', '357', 'https://openapi.tuyaeu.com'),
    country('Czech Republic', '420', 'https://openapi.tuyaeu.com'),
    country('Democratic Republic of the Congo', '243', 'https://openapi.tuyaeu.com'),
    country('Denmark', '45', 'https://openapi.tuyaeu.com'),
    country('Djibouti', '253', 'https://openapi.tuyaeu.com'),
    country('Dominica', '1-767', 'https://openapi.tuyaeu.com'),
    country('Dominican Republic', '1-809', 'https://openapi.tuyaus.com'),
    country('East Timor', '670', 'https://openapi.tuyaus.com'),
    country('Ecuador', '593', 'https://openapi.tuyaus.com'),
    country('Egypt', '20', 'https://openapi.tuyaeu.com'),
    country('El Salvador', '503', 'https://openapi.tuyaeu.com'),
    country('Equatorial Guinea', '240', 'https://openapi.tuyaeu.com'),
    country('Eritrea', '291', 'https://openapi.tuyaeu.com'),
    country('Estonia', '372', 'https://openapi.tuyaeu.com'),
    country('Ethiopia', '251', 'https://openapi.tuyaeu.com'),
    country('Falkland Islands', '500', 'https://openapi.tuyaus.com'),
    country('Faroe Islands', '298', 'https://openapi.tuyaeu.com'),
    country('Fiji', '679', 'https://openapi.tuyaeu.com'),
    country('Finland', '358', 'https://openapi.tuyaeu.com'),
    country('France', '33', 'https://openapi.tuyaeu.com'),
    country('French Polynesia', '689', 'https://openapi.tuyaeu.com'),
    country('Gabon', '241', 'https://openapi.tuyaeu.com'),
    country('Gambia', '220', 'https://openapi.tuyaeu.com'),
    country('Georgia', '995', 'https://openapi.tuyaeu.com'),
    country('Germany', '49', 'https://openapi.tuyaeu.com'),
    country('Ghana', '233', 'https://openapi.tuyaeu.com'),
    country('Gibraltar', '350', 'https://openapi.tuyaeu.com'),
    country('Greece', '30', 'https://openapi.tuyaeu.com'),
    country('Greenland', '299', 'https://openapi.tuyaeu.com'),
    country('Grenada', '1-473', 'https://openapi.tuyaeu.com'),
    country('Guam', '1-671', 'https://openapi.tuyaeu.com'),
    country('Guatemala', '502', 'https://openapi.tuyaus.com'),
    country('Guernsey', '44-1481'),
    country('Guinea', '224'),
    country('Guinea-Bissau', '245', 'https://openapi.tuyaus.com'),
    country('Guyana', '592', 'https://openapi.tuyaeu.com'),
    country('Haiti', '509', 'https://openapi.tuyaeu.com'),
    country('Honduras', '504', 'https://openapi.tuyaeu.com'),
    country('Hong Kong', '852', 'https://openapi.tuyaus.com'),
    country('Hungary', '36', 'https://openapi.tuyaeu.com'),
    country('Iceland', '354', 'https://openapi.tuyaeu.com'),
    country('India', '91', 'https://openapi.tuyain.com'),
    country('Indonesia', '62', 'https://openapi.tuyaus.com'),
    country('Iran', '98'),
    country('Iraq', '964', 'https://openapi.tuyaeu.com'),
    country('Ireland', '353', 'https://openapi.tuyaeu.com'),
    country('Isle of Man', '44-1624'),
    country('Israel', '972', 'https://openapi.tuyaeu.com'),
    country('Italy', '39', 'https://openapi.tuyaeu.com'),
    country('Ivory Coast', '225', 'https://openapi.tuyaeu.com'),
    country('Jamaica', '1-876', 'https://openapi.tuyaeu.com'),
    country('Japan', '81', 'https://openapi.tuyaus.com'),
    country('Jersey', '44-1534'),
    country('Jordan', '962', 'https://openapi.tuyaeu.com'),
    country('Kazakhstan', '7', 'https://openapi.tuyaeu.com'),
    country('Kenya', '254', 'https://openapi.tuyaeu.com'),
    country('Kiribati', '686', 'https://openapi.tuyaus.com'),
    country('Kosovo', '383'),
    country('Kuwait', '965', 'https://openapi.tuyaeu.com'),
    country('Kyrgyzstan', '996', 'https://openapi.tuyaeu.com'),
    country('Laos', '856', 'https://openapi.tuyaeu.com'),
    country('Latvia', '371', 'https://openapi.tuyaeu.com'),
    country('Lebanon', '961', 'https://openapi.tuyaeu.com'),
    country('Lesotho', '266', 'https://openapi.tuyaeu.com'),
    country('Liberia', '231', 'https://openapi.tuyaeu.com'),
    country('Libya', '218', 'https://openapi.tuyaeu.com'),
    country('Liechtenstein', '423', 'https://openapi.tuyaeu.com'),
    country('Lithuania', '370', 'https://openapi.tuyaeu.com'),
    country('Luxembourg', '352', 'https://openapi.tuyaeu.com'),
    country('Macao', '853', 'https://openapi.tuyaus.com'),
    country('Macedonia', '389', 'https://openapi.tuyaeu.com'),
    country('Madagascar', '261', 'https://openapi.tuyaeu.com'),
    country('Malawi', '265', 'https://openapi.tuyaeu.com'),
    country('Malaysia', '60', 'https://openapi.tuyaus.com'),
    country('Maldives', '960', 'https://openapi.tuyaeu.com'),
    country('Mali', '223', 'https://openapi.tuyaeu.com'),
    country('Malta', '356', 'https://openapi.tuyaeu.com'),
    country('Marshall Islands', '692', 'https://openapi.tuyaeu.com'),
    country('Mauritania', '222', 'https://openapi.tuyaeu.com'),
    country('Mauritius', '230', 'https://openapi.tuyaeu.com'),
    country('Mayotte', '262', 'https://openapi.tuyaeu.com'),
    country('Mexico', '52', 'https://openapi.tuyaus.com'),
    country('Micronesia', '691', 'https://openapi.tuyaeu.com'),
    country('Moldova', '373', 'https://openapi.tuyaeu.com'),
    country('Monaco', '377', 'https://openapi.tuyaeu.com'),
    country('Mongolia', '976', 'https://openapi.tuyaeu.com'),
    country('Montenegro', '382', 'https://openapi.tuyaeu.com'),
    country('Montserrat', '1-664', 'https://openapi.tuyaeu.com'),
    country('Morocco', '212', 'https://openapi.tuyaeu.com'),
    country('Mozambique', '258', 'https://openapi.tuyaeu.com'),
    country('Myanmar', '95', 'https://openapi.tuyaus.com'),
    country('Namibia', '264', 'https://openapi.tuyaeu.com'),
    country('Nauru', '674', 'https://openapi.tuyaus.com'),
    country('Nepal', '977', 'https://openapi.tuyaeu.com'),
    country('Netherlands', '31', 'https://openapi.tuyaeu.com'),
    country('Netherlands Antilles', '599'),
    country('New Caledonia', '687', 'https://openapi.tuyaeu.com'),
    country('New Zealand', '64', 'https://openapi.tuyaus.com'),
    country('Nicaragua', '505', 'https://openapi.tuyaeu.com'),
    country('Niger', '227', 'https://openapi.tuyaeu.com'),
    country('Nigeria', '234', 'https://openapi.tuyaeu.com'),
    country('Niue', '683', 'https://openapi.tuyaus.com'),
    country('North Korea', '850'),
    country('Northern Mariana Islands', '1-670', 'https://openapi.tuyaeu.com'),
    country('Norway', '47', 'https://openapi.tuyaeu.com'),
    country('Oman', '968', 'https://openapi.tuyaeu.com'),
    country('Pakistan', '92', 'https://openapi.tuyaeu.com'),
    country('Palau', '680', 'https://openapi.tuyaeu.com'),
    country('Palestine', '970', 'https://openapi.tuyaus.com'),
    country('Panama', '507', 'https://openapi.tuyaeu.com'),
    country('Papua New Guinea', '675', 'https://openapi.tuyaus.com'),
    country('Paraguay', '595', 'https://openapi.tuyaus.com'),
    country('Peru', '51', 'https://openapi.tuyaus.com'),
    country('Philippines', '63', 'https://openapi.tuyaus.com'),
    country('Pitcairn', '64'),
    country('Poland', '48', 'https://openapi.tuyaeu.com'),
    country('Portugal', '351', 'https://openapi.tuyaeu.com'),
    country('Puerto Rico', '1-787, 1-939', 'https://openapi.tuyaus.com'),
    country('Qatar', '974', 'https://openapi.tuyaeu.com'),
    country('Republic of the Congo', '242', 'https://openapi.tuyaeu.com'),
    country('Reunion', '262', 'https://openapi.tuyaeu.com'),
    country('Romania', '40', 'https://openapi.tuyaeu.com'),
    country('Russia', '7', 'https://openapi.tuyaeu.com'),
    country('Rwanda', '250', 'https://openapi.tuyaeu.com'),
    country('Saint Barthelemy', '590', 'https://openapi.tuyaeu.com'),
    country('Saint Helena', '290'),
    country('Saint Kitts and Nevis', '1-869', 'https://openapi.tuyaeu.com'),
    country('Saint Lucia', '1-758', 'https://openapi.tuyaeu.com'),
    country('Saint Martin', '590', 'https://openapi.tuyaeu.com'),
    country('Saint Pierre and Miquelon', '508', 'https://openapi.tuyaeu.com'),
    country('Saint Vincent and the Grenadines', '1-784', 'https://openapi.tuyaeu.com'),
    country('Samoa', '685', 'https://openapi.tuyaeu.com'),
    country('San Marino', '378', 'https://openapi.tuyaeu.com'),
    country('Sao Tome and Principe', '239', 'https://openapi.tuyaus.com'),
    country('Saudi Arabia', '966', 'https://openapi.tuyaeu.com'),
    country('Senegal', '221', 'https://openapi.tuyaeu.com'),
    country('Serbia', '381', 'https://openapi.tuyaeu.com'),
    country('Seychelles', '248', 'https://openapi.tuyaeu.com'),
    country('Sierra Leone', '232', 'https://openapi.tuyaeu.com'),
    country('Singapore', '65', 'https://openapi.tuyaeu.com'),
    country('Sint Maarten', '1-721', 'https://openapi.tuyaus.com'),
    country('Slovakia', '421', 'https://openapi.tuyaeu.com'),
    country('Slovenia', '386', 'https://openapi.tuyaeu.com'),
    country('Solomon Islands', '677', 'https://openapi.tuyaus.com'),
    country('Somalia', '252', 'https://openapi.tuyaeu.com'),
    country('South Africa', '27', 'https://openapi.tuyaeu.com'),
    country('South Korea', '82', 'https://openapi.tuyaus.com'),
    country('South Sudan', '211'),
    country('Spain', '34', 'https://openapi.tuyaeu.com'),
    country('Sri Lanka', '94', 'https://openapi.tuyaeu.com'),
    country('Sudan', '249'),
    country('Suriname', '597', 'https://openapi.tuyaus.com'),
    country('Svalbard and Jan Mayen', '4779', 'https://openapi.tuyaus.com'),
    country('Swaziland', '268', 'https://openapi.tuyaeu.com'),
    country('Sweden', '46', 'https://openapi.tuyaeu.com'),
    country('Switzerland', '41', 'https://openapi.tuyaeu.com'),
    country('Syria', '963'),
    country('Taiwan', '886', 'https://openapi.tuyaus.com'),
    country('Tajikistan', '992', 'https://openapi.tuyaeu.com'),
    country('Tanzania', '255', 'https://openapi.tuyaeu.com'),
    country('Thailand', '66', 'https://openapi.tuyaus.com'),
    country('Togo', '228', 'https://openapi.tuyaeu.com'),
    country('Tokelau', '690', 'https://openapi.tuyaus.com'),
    country('Tonga', '676', 'https://openapi.tuyaeu.com'),
    country('Trinidad and Tobago', '1-868', 'https://openapi.tuyaeu.com'),
    country('Tunisia', '216', 'https://openapi.tuyaeu.com'),
    country('Turkey', '90', 'https://openapi.tuyaeu.com'),
    country('Turkmenistan', '993', 'https://openapi.tuyaeu.com'),
    country('Turks and Caicos Islands', '1-649', 'https://openapi.tuyaeu.com'),
    country('Tuvalu', '688', 'https://openapi.tuyaeu.com'),
    country('U.S. Virgin Islands', '1-340', 'https://openapi.tuyaeu.com'),
    country('Uganda', '256', 'https://openapi.tuyaeu.com'),
    country('Ukraine', '380', 'https://openapi.tuyaeu.com'),
    country('United Arab Emirates', '971', 'https://openapi.tuyaeu.com'),
    country('United Kingdom', '44', 'https://openapi.tuyaeu.com'),
    country('United States', '1', 'https://openapi.tuyaus.com'),
    country('Uruguay', '598', 'https://openapi.tuyaus.com'),
    country('Uzbekistan', '998', 'https://openapi.tuyaeu.com'),
    country('Vanuatu', '678', 'https://openapi.tuyaus.com'),
    country('Vatican', '379', 'https://openapi.tuyaeu.com'),
    country('Venezuela', '58', 'https://openapi.tuyaus.com'),
    country('Vietnam', '84', 'https://openapi.tuyaus.com'),
    country('Wallis and Futuna', '681', 'https://openapi.tuyaeu.com'),
    country('Western Sahara', '212', 'https://openapi.tuyaeu.com'),
    country('Yemen', '967', 'https://openapi.tuyaeu.com'),
    country('Zambia', '260', 'https://openapi.tuyaeu.com'),
    country('Zimbabwe', '263', 'https://openapi.tuyaeu.com')
]

/**
 *  Driver Capabilities Implementation
 */
private void createChildDevices(Map d) {
    Map driver = mapTuyaCategory(d)
    if (!driver.devices) {
        createChildDevice("${device.id}-${d.id}", driver, d)
        return
    }

    // Multiple child device support
    String baseName = d.name
    Map baseFunctions = d.functions
    Map baseStatusSet = d.statusSet
    Map subdevices = driver.devices.findAll { entry -> entry.key in baseFunctions.keySet() }
    subdevices.each { code, description ->
        d.name = "${baseName} ${description}"
        d.functions = [ (code): baseFunctions[(code)] ]
        d.statusSet = [ (code): baseStatusSet[(code)] ]
        createChildDevice("${device.id}-${d.id}-${code}", driver, d)
    }
}

private ChildDeviceWrapper createChildDevice(String dni, Map driver, Map d) {
    ChildDeviceWrapper dw = getChildDevice(dni)
    if (!dw) {
        log.info "${device} creating device ${d.name} using ${driver.name} driver"
        try {
            dw = addChildDevice(driver.namespace, driver.name, dni,
                [
                    name: d.product_name,
                    label: d.name,
                    //location: add room support if we can get from Tuya
                ]
            )
        } catch (UnknownDeviceTypeException e) {
            log.warn "${device} ${e.message} - you may need to install the driver"
        }
    }

    String functionJson = JsonOutput.toJson(d.functions)
    jsonCache.put(functionJson, d.functions)
    dw?.with {
        label = label ?: d.name
        updateDataValue 'id', d.id
        updateDataValue 'local_key', d.local_key
        updateDataValue 'product_id', d.product_id
        updateDataValue 'category', d.category
        updateDataValue 'functions', functionJson
        updateDataValue 'statusSet', JsonOutput.toJson(d.statusSet)
        updateDataValue 'online', d.online as String
    }

    return dw
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void logsOff() {
    device.updateSetting('logEnable', [value: 'false', type: 'bool'] )
    log.info "${device} debug logging disabled"
}

private void parseBizData(String bizCode, Map bizData) {
    String indicator = ' [Offline]'
    ChildDeviceWrapper dw = bizData.devId ? getChildDevice("${device.id}-${bizData.devId}") : null

    if (logEnable) { log.debug "${device} ${bizCode} ${bizData}" }
    switch (bizCode) {
        case 'nameUpdate':
            dw.label = bizData.name
            break
        case 'online':
            dw.updateDataValue('online', 'true')
            if (dw.label.endsWith(indicator)) {
                dw.label -= indicator
            }
            break
        case 'offline':
            dw.updateDataValue('online', 'false')
            if (!dw.label.endsWith(indicator)) {
                dw.label += indicator
            }
            break
        case 'bindUser':
            refresh()
            break
    }
}

private void updateMultiDeviceStatus(Map d) {
    ChildDeviceWrapper dw
    String baseDni = "${device.id}-${d.id ?: d.devId}"
    List<Map> events = []
    for (Map newState in d.status) {
        String dni = "${baseDni}-${newState.code}"
        if ((dw = getChildDevice(dni))) {
            events = createEvents(dw, [ newState ])
        } else if ((dw = getChildDevice(baseDni))) {
            events = createEvents(dw, d.status)
        } else {
            log.error "${device} Unable to find device for ${d}"
            continue
        }

        if (events && logEnable) { log.debug "${device} ${dw} sending events ${events}" }
        dw.parse(events)
    }
}

private List<Map> createEvents(DeviceWrapper dw, List<Map> statusList) {
    String workMode = statusList['workMode'] ?: ''
    Map<String, Map> deviceStatusSet = getStatusSet(dw) ?: getFunctions(dw)
    return statusList.collectMany { status ->
        if (logEnable) { log.debug "${device} ${dw} status ${status}" }

        if (status.code in tuyaFunctions.battery) {
            if (status.code == 'battery_percentage' || status.code == 'va_battery') {
                if (txtEnable) { log.info "${dw} battery is ${status.value}%" }
                return [ [ name: 'battery', value: status.value, descriptionText: "battery is ${status.value}%", unit: '%' ] ]
            }
        }

        if (status.code in tuyaFunctions.brightness && workMode != 'colour') {
            Map bright = deviceStatusSet[status.code] ?: defaults[status.code]
            if ( bright != null ) {
                Integer value = Math.floor(remap(status.value, bright.min, bright.max, 0, 100))
                if (txtEnable) { log.info "${dw} level is ${value}%" }
                return [ [ name: 'level', value: value, unit: '%', descriptionText: "level is ${value}%" ] ]
            }
        }

        if (status.code in tuyaFunctions.co) {
            String value = status.value == 'alarm' ? 'detected' : 'clear'
            if (txtEnable) { log.info "${dw} carbon monoxide is ${value}" }
            return [ [ name: 'carbonMonoxide', value: value, descriptionText: "carbon monoxide is ${value}" ] ]
        }

        if (status.code in tuyaFunctions.co2) {
            String value = status.value
            if (txtEnable) { log.info "${dw} carbon dioxide level is ${value}" }
            return [ [ name: 'carbonDioxide', value: value, unit: 'ppm', descriptionText: "carbon dioxide level is ${value}" ] ]
        }

        if (status.code in tuyaFunctions.control + tuyaFunctions.workState) {
            String value
            switch (status.value) {
                case 'open': value = 'open'; break
                case 'opening': value = 'opening'; break
                case 'close': value = 'closed'; break
                case 'closing': value = 'closing'; break
                case 'stop': value = 'unknown'; break
            }
            if (value) {
                if (txtEnable) { log.info "${dw} control is ${value}" }
                return [ [ name: 'windowShade', value: value, descriptionText: "window shade is ${value}" ] ]
            }
        }

        if (status.code in tuyaFunctions.ct) {
            Map temperature = deviceStatusSet[status.code] ?: defaults[status.code]
            Integer value = Math.floor(1000000 / remap(temperature.max - status.value,
                            temperature.min, temperature.max, minMireds, maxMireds))
            if (txtEnable) { log.info "${dw} color temperature is ${value}K" }
            return [ [ name: 'colorTemperature', value: value, unit: 'K',
                       descriptionText: "color temperature is ${value}K" ] ]
        }

        if (status.code in tuyaFunctions.colour) {
            Map colour = deviceStatusSet[status.code] ?: defaults[status.code]
            Map bright = getFunction(deviceStatusSet, tuyaFunctions.brightness) ?: colour.v
            Map value = status.value == '' ? [h: 100.0, s: 100.0, v: 100.0] :
                        jsonCache.computeIfAbsent(status.value) { k -> new JsonSlurper().parseText(k) }
            Integer hue = Math.floor(remap(value.h, colour.h.min, colour.h.max, 0, 100))
            Integer saturation = Math.floor(remap(value.s, colour.s.min, colour.s.max, 0, 100))
            Integer level = Math.floor(remap(value.v, bright.min, bright.max, 0, 100))
            String colorName = translateColorName(hue, saturation)
            if (txtEnable) { log.info "${dw} color is h:${hue} s:${saturation} (${colorName})" }
            List<Map> events = [
                [ name: 'hue', value: hue, descriptionText: "hue is ${hue}" ],
                [ name: 'saturation', value: saturation, descriptionText: "saturation is ${saturation}" ],
                [ name: 'colorName', value: colorName, descriptionText: "color name is ${colorName}" ]
            ]
            if (workMode == 'color') {
                if (txtEnable) { log.info "${dw} level is ${level}%" }
                events << [ name: 'level', value: level, unit: '%', descriptionText: "level is ${level}%" ]
            }
            return events
        }

        if (status.code in tuyaFunctions.contact) {
            String value = status.value ? 'open' : 'closed'
            if (txtEnable) { log.info "${dw} contact is ${value}" }
            return [ [ name: 'contact', value: value, descriptionText: "contact is ${value}" ] ]
        }

        if (status.code in tuyaFunctions.fanSpeed) {
            Map speed = deviceStatusSet[status.code] ?: defaults[status.code]
            int value
            if (statusList['switch']) {
                switch (speed.type) {
                    case 'Enum':
                        value = remap(speed.range.indexOf(status.value), 0, speed.range.size() - 1, 0, 4) as int
                        break
                    case 'Integer':
                        int min = (speed.min == null) ? 1 : speed.min
                        int max = (speed.max == null) ? 100 : speed.max
                        value = remap(status.value as int, min, max, 0, 4) as int
                        break
                }
                String level = ['low', 'medium-low', 'medium', 'medium-high', 'high'].get(value)
                if (txtEnable) { log.info "${dw} speed is ${level}" }
                return [ [ name: 'speed', value: level, descriptionText: "speed is ${level}" ] ]
            }

            if (txtEnable) { log.info "${dw} speed is off" }
            return [ [ name: 'speed', value: 'off', descriptionText: 'speed is off' ] ]
        }

        if (status.code in tuyaFunctions.light || status.code in tuyaFunctions.power) {
            String value = status.value ? 'on' : 'off'
            if (txtEnable) { log.info "${dw} switch is ${value}" }
            return [ [ name: 'switch', value: value, descriptionText: "switch is ${value}" ] ]
        }

        if (status.code in tuyaFunctions.meteringSwitch) {
            String name = status.code
            String value = status.value
            String unit = ''
            switch (status.code) {
                case 'cur_power':
                    name = 'power'
                    value = status.value / 10
                    unit = 'W'
                    break
                case 'cur_voltage':
                case 'cur_current':
                case 'relay_status':
                case 'light_mode':
                case 'add_ele':
                case 'countdown_1':
                    break
                default:
                    log.warn "${dw} unsupported meteringSwitch status.code ${status.code}"
            }
            if (name != null && value != null) {
                if (txtEnable) { log.info "${dw} ${name} is ${value} ${unit}" }
                return [ [ name: name, value: value, descriptionText: "${dw} ${name} is ${value} ${unit}", unit: unit ] ]
            }
        }

        if (status.code in tuyaFunctions.omniSensor) {
            String name
            String value
            String unit
            switch (status.code) {
                case 'bright_value':
                    name = 'illuminance'
                    value = status.value
                    unit = 'Lux'
                    break
                case 'humidity_value':
                case 'va_humidity':
                    value = status.value
                    if (status.code == 'humidity_value') {
                        value = status.value / 10
                    }
                    name = 'humidity'
                    unit = 'RH%'
                    break
                case 'bright_sensitivity':
                case 'sensitivity':
                    name = 'sensitivity'
                    value = status.value
                    unit = '%'
                    break
                case 'shock_state':    // vibration sensor TS0210
                    name = 'motion'    // simulated motion
                    value = 'active'   // no 'inactive' state!
                    unit = ''
                    status.code = 'inactive_state'
                    runIn(5, 'updateMultiDeviceStatus',  [data: d])
                    break
                case 'inactive_state': // vibration sensor
                    name = 'motion'    // simulated motion
                    value = 'inactive' // simulated 'inactive' state!
                    unit = ''
                    break
                default:
                    log.warn "${dw} unsupported omniSensor status.code ${status.code}"
            }
            if (name != null && value != null) {
                if (txtEnable) { log.info "${dw} ${name} is ${value} ${unit}" }
                return [ [ name: name, value: value, descriptionText: "${name} is ${value} ${unit}", unit: unit ] ]
            }
        }

        if (status.code in tuyaFunctions.pir) {
            String value = status.value == 'pir' ? 'active' : 'inactive'
            if (txtEnable) { log.info "${dw} motion is ${value}" }
            return [ [ name: 'motion', value: value, descriptionText: "motion is ${value}" ] ]
        }

        if (status.code in tuyaFunctions.percent_control) {
            if (txtEnable) { log.info "${dw} position is ${status.value}%" }
            return [ [ name: 'position', value: status.value, descriptionText: "position is ${status.value}%", unit: '%' ] ]
        }

        if (status.code in tuyaFunctions.sceneSwitch) {
            String action
            if (status.value in sceneSwitchAction) {
                action = sceneSwitchAction[status.value]
            } else {
                log.warn "${dw} sceneSwitch: unknown status.value ${status.value}"
            }

            String value
            if (status.code in sceneSwitchKeyNumbers) {
                value = sceneSwitchKeyNumbers[status.code]
                if (d.productKey == 'vp6clf9d' && status.code == 'switch1_value') {
                    value = '1'                    // correction for TS0044 key #1
                }
            } else {
                log.warn "${dw} sceneSwitch: unknown status.code ${status.code}"
            }

            if (value != null && action != null) {
                if (txtEnable) { log.info "${dw} buttons ${value} is ${action}" }
                return [ [ name: action, value: value, descriptionText: "button ${value} is ${action}", isStateChange: true ] ]
            }

            log.warn "${dw} sceneSwitch: unknown name ${action} or value ${value}"
        }

        if (status.code in tuyaFunctions.smoke) {
            String value = status.value == 'alarm' ? 'detected' : 'clear'
            if (txtEnable) { log.info "${dw} smoke is ${value}" }
            return [ [ name: 'smoke', value: value, descriptionText: "smoke is ${value}" ] ]
        }

        if (status.code in tuyaFunctions.temperature) {
            Map set = deviceStatusSet[status.code] ?: defaults[status.code]
            int scale = Math.pow(10, set.scale as int)
            String value = status.value / scale
            String unit = set.unit
            if (txtEnable) { log.info "${dw} temperature is ${value}${unit}" }
            return [ [ name: 'temperature', value: value, unit: unit, descriptionText: "temperature is ${value}${unit}" ] ]
        }

        if (status.code in tuyaFunctions.temperatureSet) {
            Map set = deviceStatusSet[status.code] ?: defaults[status.code]
            String value = status.value
            String unit = set.unit
            if (txtEnable) { log.info "${dw} heating set point is ${value}${unit}" }
            return [ [ name: 'heatingSetpoint', value: value, unit: unit, descriptionText: "heating set point is ${value}${unit}" ] ]
        }

        if (status.code in tuyaFunctions.water) {
            String value = status.value == 'alarm' ? 'wet' : 'dry'
            if (txtEnable) { log.info "${dw} water is ${value}" }
            return [ [ name: 'water', value: value, descriptionText: "water is ${value}" ] ]
        }

        if (status.code in tuyaFunctions.workMode) {
            switch (status.value) {
                case 'white':
                case 'light_white':
                    if (txtEnable) { log.info "${dw} color mode is CT" }
                    return [ [ name: 'colorMode', value: 'CT', descriptionText: 'color mode is CT' ] ]
                case 'colour':
                    if (txtEnable) { log.info "${dw} color mode is RGB" }
                    return [ [ name: 'colorMode', value: 'RGB', descriptionText: 'color mode is RGB' ] ]
            }
        }

        return []
    }
}

/**
 *  Tuya Open API Authentication
 *  https://developer.tuya.com/en/docs/cloud/
*/
private void tuyaAuthenticateAsync() {
    unschedule('tuyaAuthenticateAsync')
    if (settings.username && settings.password && settings.appSchema && state.countryCode) {
        log.info "${device} starting Tuya cloud authentication for ${settings.username}"
        MessageDigest digest = MessageDigest.getInstance('MD5')
        String md5pwd = HexUtils.byteArrayToHexString(digest.digest(settings.password.bytes)).toLowerCase()
        Map body = [
            'country_code': state.countryCode,
            'username': settings.username,
            'password': md5pwd,
            'schema': settings.appSchema
        ]
        state.tokenInfo.access_token = ''
        sendEvent([ name: 'state', value: 'authenticating', descriptionText: 'Authenticating to Tuya'])
        tuyaPostAsync('/v1.0/iot-01/associated-users/actions/authorized-login', body, 'tuyaAuthenticateResponse')
    } else {
        sendEvent([ name: 'state', value: 'not configured', descriptionText: 'Driver not configured'])
        log.error "${device} must be configured before authentication is possible"
    }
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void tuyaAuthenticateResponse(AsyncResponse response, Map data) {
    if (!tuyaCheckResponse(response)) {
        sendEvent([ name: 'state', value: 'error', descriptionText: 'Error authenticating to Tuya (check credentials and country)'])
        runIn(60 + random.nextInt(300), 'tuyaAuthenticateAsync')
        return
    }

    Map result = response.json.result
    state.endPoint = result.platform_url
    state.tokenInfo = [
        access_token: result.access_token,
        refresh_token: result.refresh_token,
        uid: result.uid,
        expire: result.expire_time * 1000 + now(),
    ]
    log.info "${device} received Tuya access token (valid for ${result.expire_time}s)"
    sendEvent([ name: 'state', value: 'authenticated', descriptionText: 'Authenticated to Tuya'])

    // Schedule next authentication
    runIn(result.expire_time - 60, 'tuyaAuthenticateAsync')

    // Get MQTT details
    tuyaGetHubConfigAsync()
}

/**
 *  Tuya Open API Device Management
 *  https://developer.tuya.com/en/docs/cloud/
 *
 *  Attributes:
 *      id: Device id
 *      name: Device name
 *      local_key: Key
 *      category: Product category
 *      product_id: Product ID
 *      product_name: Product name
 *      sub: Determine whether it is a sub-device, true-> yes; false-> no
 *      uuid: The unique device identifier
 *      asset_id: asset id of the device
 *      online: Online status of the device
 *      icon: Device icon
 *      ip: Device external IP
 *      time_zone: device time zone
 *      active_time: The last pairing time of the device
 *      create_time: The first network pairing time of the device
 *      update_time: The update time of device status
 *      status: Status set of the device
 */
private void tuyaGetDevicesAsync(String last_row_key = '', Map data = [:]) {
    if (!jsonCache.isEmpty()) {
        log.info "${device} clearing json cache"
        jsonCache.clear()
    }

    log.info "${device} requesting cloud devices batch"
    tuyaGetAsync('/v1.0/iot-01/associated-users/devices', [ 'last_row_key': last_row_key ], 'tuyaGetDevicesResponse', data)
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void tuyaGetDevicesResponse(AsyncResponse response, Map data) {
    if (tuyaCheckResponse(response)) {
        Map result = response.json.result
        data.devices = (data.devices ?: []) + result.devices
        log.info "${device} received ${result.devices.size()} cloud devices (has_more: ${result.has_more})"
        if (result.has_more) {
            pauseExecution(1000)
            tuyaGetDevicesAsync(result.last_row_key, data)
            return
        }
    }

    sendEvent([ name: 'deviceCount', value: data.devices?.size() as String ])
    data.devices.each { d ->
        tuyaGetDeviceSpecificationsAsync(d.id, d)
    }
}

// https://developer.tuya.com/en/docs/cloud/device-control?id=K95zu01ksols7#title-29-API%20address
private void tuyaGetDeviceSpecificationsAsync(String deviceID, Map data = [:]) {
    log.info "${device} requesting cloud device specifications for ${deviceID}"
    tuyaGetAsync("/v1.0/devices/${deviceID}/specifications", null, 'tuyaGetDeviceSpecificationsResponse', data)
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void tuyaGetDeviceSpecificationsResponse(AsyncResponse response, Map data) {
    if (tuyaCheckResponse(response)) {
        JsonSlurper parser = new JsonSlurper()
        Map result = response.json.result
        data.category = result.category
        if (result.functions) {
            data.functions = result.functions.collectEntries { f ->
                Map values = parser.parseText(f.values ?: '{}')
                values.type = f.type
                return [ (f.code): values ]
            }
        } else {
            data.functions = [:]
        }
        if (result.status) {
            data.statusSet = result.status.collectEntries { f ->
                Map values = parser.parseText(f.values ?: '{}')
                values.type = f.type
                return [ (f.code): values ]
            }
        } else {
            data.statusSet = [:]
        }

        if (logEnable) { log.debug "${device} Device Data: ${data}"}
        createChildDevices(data)
        updateMultiDeviceStatus(data)

        if (device.currentValue('state') != 'ready') {
            sendEvent([ name: 'state', value: 'ready', descriptionText: 'Received device data from Tuya'])
        }
    }
}

private void tuyaGetStateAsync(String deviceID) {
    if (logEnable) { log.debug "${device} requesting device ${deviceID} state" }
    tuyaGetAsync("/v1.0/devices/${deviceID}/status", null, 'tuyaGetStateResponse', [ id: deviceID ])
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void tuyaGetStateResponse(AsyncResponse response, Map data) {
    if (!tuyaCheckResponse(response)) { return }
    data.status = response.json.result
    updateMultiDeviceStatus(data)
}

private void tuyaSendDeviceCommandsAsync(String deviceID, Map...params) {
    if (logEnable) { log.debug "${device} device ${deviceID} command ${params}" }
    if (!state?.tokenInfo?.access_token) {
        log.error "${device} tuyaSendDeviceCommandsAsync Error - Access token is null"
        sendEvent([ name: 'state', value: 'error', descriptionText: 'Access token not set (failed login?)'])
        return
    }
    tuyaPostAsync("/v1.0/devices/${deviceID}/commands", [ 'commands': params ], 'tuyaSendDeviceCommandsResponse')
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void tuyaSendDeviceCommandsResponse(AsyncResponse response, Map data) {
    if (!tuyaCheckResponse(response)) {
        sendEvent([ name: 'state', value: 'error', descriptionText: 'Error sending device command'])
        runIn(5, 'tuyaHubConnectAsync')
    }
}

/**
 *  Tuya Open API MQTT Hub
 *  https://developer.tuya.com/en/docs/cloud/
 */
private void tuyaGetHubConfigAsync() {
    log.info "${device} requesting Tuya MQTT configuration"
    Map body = [
        'uid': state.tokenInfo.uid,
        'link_id': state.uuid,
        'link_type': 'mqtt',
        'topics': 'device',
        'msg_encrypted_version': '1.0'
    ]

    tuyaPostAsync('/v1.0/iot-03/open-hub/access-config', body, 'tuyaGetHubConfigResponse')
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void tuyaGetHubConfigResponse(AsyncResponse response, Map data) {
    if (!tuyaCheckResponse(response)) { return }
    Map result = response.json.result
    state.mqttInfo = result
    tuyaHubConnectAsync()
}

private void tuyaHubConnectAsync() {
    log.info "${device} connecting to Tuya MQTT hub at ${state.mqttInfo.url}"
    try {
        interfaces.mqtt.connect(
            state.mqttInfo.url,
            state.mqttInfo.client_id,
            state.mqttInfo.username,
            state.mqttInfo.password)
    } catch (e) {
        log.error "${device} MQTT connection error: " + e
        runIn(15 + random.nextInt(45), 'tuyaHubConnectAsync')
    }
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void tuyaHubSubscribeAsync() {
    state.mqttInfo.source_topic.each { t ->
        log.info "${device} subscribing to Tuya MQTT hub ${t.key} topic"
        interfaces.mqtt.subscribe(t.value)
    }

    tuyaGetDevicesAsync()
}

/**
 *  Tuya Open API HTTP REST Implementation
 *  https://developer.tuya.com/en/docs/cloud/
 */
private void tuyaGetAsync(String path, Map query, String callback, Map data = [:]) {
    tuyaRequestAsync('get', path, callback, query, null, data)
}

private void tuyaPostAsync(String path, Map body, String callback, Map data = [:]) {
    tuyaRequestAsync('post', path, callback, null, body, data)
}

private void tuyaRequestAsync(String method, String path, String callback, Map query, Map body, Map data) {
    String accessToken = state?.tokenInfo?.access_token ?: ''
    String stringToSign = tuyaGetStringToSign(method, path, query, body)
    long now = now()
    Map headers = [
      't': now,
      'nonce': state.uuid,
      'client_id': access_id,
      'Signature-Headers': 'client_id',
      'sign': tuyaCalculateSignature(accessToken, now, stringToSign),
      'sign_method': 'HMAC-SHA256',
      'access_token': accessToken,
      'lang': state.lang, // use zh for china
      'dev_lang': 'groovy',
      'dev_channel': 'hubitat',
      'devVersion': state.driver_version
    ]

    Map request = [
        uri: state.endPoint,
        path: path,
        query: query,
        contentType: 'application/json',
        headers: headers,
        body: JsonOutput.toJson(body),
        timeout: 5
    ]

    if (logEnable) {
        log.debug("${device} API ${method.toUpperCase()} ${request}")
    }

    switch (method) {
        case 'get': asynchttpGet(callback, request, data); break
        case 'post': asynchttpPost(callback, request, data); break
    }
}

private boolean tuyaCheckResponse(AsyncResponse response) {
    if (response.hasError()) {
        log.error "${device} cloud request error ${response.getErrorMessage()}"
        sendEvent([ name: 'state', value: 'error', descriptionText: response.getErrorMessage() ])
        return false
    }

    if (response.status != 200) {
        log.error "${device} cloud request returned HTTP status ${response.status}"
        sendEvent([ name: 'state', value: 'error', descriptionText: "Cloud HTTP response ${response.status}" ])
        return false
    }

    if (response.json?.success != true) {
        log.error "${device} cloud API request failed: ${response.data}"
        sendEvent([ name: 'state', value: 'error', descriptionText: response.data ])
        return false
    }

    if (logEnable) {
        log.debug "${device} API response ${response.json ?: response.data}"
    }

    return true
}

private String tuyaCalculateSignature(String accessToken, long timestamp, String stringToSign) {
    String message = access_id + accessToken + timestamp.toString() + state.uuid + stringToSign
    Mac sha256HMAC = Mac.getInstance('HmacSHA256')
    sha256HMAC.init(new SecretKeySpec(access_key.bytes, 'HmacSHA256'))
    return HexUtils.byteArrayToHexString(sha256HMAC.doFinal(message.bytes))
}

private String tuyaGetStringToSign(String method, String path, Map query, Map body) {
    String url = query ? path + '?' + query.sort().collect { key, value -> "${key}=${value}" }.join('&') : path
    String headers = 'client_id:' + access_id + '\n'
    String bodyStream = (body == null) ? '' : JsonOutput.toJson(body)
    MessageDigest sha256 = MessageDigest.getInstance('SHA-256')
    String contentSHA256 = HexUtils.byteArrayToHexString(sha256.digest(bodyStream.bytes)).toLowerCase()
    return method.toUpperCase() + '\n' + contentSHA256 + '\n' + headers + '\n' + url
}
