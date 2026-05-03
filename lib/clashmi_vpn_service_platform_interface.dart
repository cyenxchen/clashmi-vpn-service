import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'clashmi_vpn_service_method_channel.dart';

abstract class ClashmiVpnServicePlatform extends PlatformInterface {
  /// Constructs a ClashmiVpnServicePlatform.
  ClashmiVpnServicePlatform() : super(token: _token);

  static final Object _token = Object();

  static ClashmiVpnServicePlatform _instance = MethodChannelClashmiVpnService();

  /// The default instance of [ClashmiVpnServicePlatform] to use.
  ///
  /// Defaults to [MethodChannelClashmiVpnService].
  static ClashmiVpnServicePlatform get instance => _instance;

  /// Platform-specific implementations should set this with their own
  /// platform-specific class that extends [ClashmiVpnServicePlatform] when
  /// they register themselves.
  static set instance(ClashmiVpnServicePlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  Future<String?> getPlatformVersion() {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }
}
