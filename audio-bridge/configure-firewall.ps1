param([int]$Port = 8765, [Parameter(Mandatory=$true)][string]$PythonExe,
      [Parameter(Mandatory=$true)][string]$Addresses, [switch]$CheckOnly)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$localAddresses = @($Addresses.Split(','))
$tcpName = "FOSA-LAN-TCP-$Port"
$udpName = "FOSA-LAN-UDP-$Port"
function Is-FosaRule($name, $protocol) {
  $r = Get-NetFirewallRule -PolicyStore ActiveStore -Name $name -ErrorAction SilentlyContinue
  if (!$r -or $r.Enabled -ne 'True' -or $r.Action -ne 'Allow' -or $r.Direction -ne 'Inbound') { return $false }
  $app = $r | Get-NetFirewallApplicationFilter
  $ports = $r | Get-NetFirewallPortFilter
  $scope = $r | Get-NetFirewallAddressFilter
  return ($app.Program -eq $PythonExe -and $ports.Protocol -eq $protocol -and
    ($protocol -eq 'UDP' -or $ports.LocalPort -eq "$Port") -and
    @($scope.RemoteAddress).Count -eq 1 -and $scope.RemoteAddress -eq 'LocalSubnet' -and
    (@(Compare-Object @($scope.LocalAddress) $localAddresses).Count -eq 0))
}
if (!$CheckOnly) {
  # Replace only FOSA's own legacy rules, which previously allowed all remote addresses.
  Get-NetFirewallRule -DisplayName 'FOSA Audio TCP 8765','FOSA Audio WebRTC UDP' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
  foreach ($name in @($tcpName, $udpName)) {
    Get-NetFirewallRule -Name $name -ErrorAction SilentlyContinue | Remove-NetFirewallRule
  }
  New-NetFirewallRule -Name $tcpName -DisplayName 'FOSA · interface LAN' -Direction Inbound -Action Allow -Enabled True `
    -Program $PythonExe -Protocol TCP -LocalPort $Port -LocalAddress $localAddresses -RemoteAddress LocalSubnet -Profile Any | Out-Null
  # aiortc allocates dynamic UDP ports; application + LAN addresses scope the permission.
  New-NetFirewallRule -Name $udpName -DisplayName 'FOSA · audio WebRTC LAN' -Direction Inbound -Action Allow -Enabled True `
    -Program $PythonExe -Protocol UDP -LocalAddress $localAddresses -RemoteAddress LocalSubnet -Profile Any | Out-Null
}
@{ok=((Is-FosaRule $tcpName 'TCP') -and (Is-FosaRule $udpName 'UDP'))} | ConvertTo-Json -Compress
