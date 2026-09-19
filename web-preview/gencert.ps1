$cert = New-SelfSignedCertificate -DnsName '10.73.88.166', 'localhost', '127.0.0.1' -CertStoreLocation 'Cert:\CurrentUser\My'
$pwd = ConvertTo-SecureString -String 'offline_mesh_123' -Force -AsPlainText
Export-PfxCertificate -Cert $cert -FilePath 'c:\Users\wahab\Documents\antigravity\sharp-bose\web-preview\cert\cert.pfx' -Password $pwd
Write-Output "PFX Certificate created successfully"
