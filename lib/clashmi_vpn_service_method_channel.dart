import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'clashmi_vpn_service_platform_interface.dart';

/// An implementation of [ClashmiVpnServicePlatform] that uses method channels.
class MethodChannelClashmiVpnService extends ClashmiVpnServicePlatform {
  /// The method channel used to interact with the native platform.
  @visibleForTesting
  final methodChannel = const MethodChannel('clashmi_vpn_service');

  @override
  Future<String?> getPlatformVersion() async {
    final version = await methodChannel.invokeMethod<String>(
      'getPlatformVersion',
    );
    return version;
  }
}
