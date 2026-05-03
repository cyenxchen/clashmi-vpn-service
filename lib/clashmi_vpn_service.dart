import 'clashmi_vpn_service_platform_interface.dart';

export 'proxy_manager.dart';
export 'state.dart';
export 'vpn_service.dart';

class ClashmiVpnService {
  Future<String?> getPlatformVersion() {
    return ClashmiVpnServicePlatform.instance.getPlatformVersion();
  }
}
