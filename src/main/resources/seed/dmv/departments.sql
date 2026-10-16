-- NH Division of Motor Vehicles bureaus for the dmv domain profile (application-dmv.yml). They're
-- rows in the departments table: code and schema call every routing unit a department, and the UI
-- shows "Bureau" via aigre.domain.unit-label. The list follows Saf-C 102.08(c), which organizes the
-- division into bureaus; Pupil Transportation and Driver & Traffic Safety Education are left out of
-- this deployment by choice.
--
-- jurisdiction_notes is the classifier's DEPARTMENTS prompt text verbatim (DepartmentDirectory),
-- so the boundary sentences between commonly-confused bureaus are deliberate, not filler.
INSERT INTO departments (id, name, short_name, jurisdiction_notes) VALUES
    ('REG', 'Registration Bureau', 'Registration',
     'vehicle registration and renewals, license plates (standard, vanity, specialty), registration permits, hardship registrations, apportioned registration for interstate commercial fleets (IRP), registration fees and municipal agent issues. REG owns anything about a vehicle''s registration or plates even when a title is mentioned in passing; ownership paperwork itself (titles, liens, VIN problems) is TITLE.'),
    ('DL', 'Driver Licensing Bureau', 'Driver Licensing',
     'driver licenses and non-driver ID cards, REAL ID, license renewals and duplicates, road and knowledge tests, commercial driver licenses (CDL) and endorsements, medical/vision requirements, ignition interlock program, out-of-state license transfers. DL issues and tests; a license that is suspended or revoked, and getting it restored, is FR.'),
    ('FR', 'Financial Responsibility Administration Bureau', 'Financial Responsibility',
     'license and registration suspensions and revocations, restoration of driving privileges and restoration fees, proof of insurance and SR-22 filings, uninsured-accident reports, administrative license suspension after an alcohol test, driver record points, protested (bounced) payment holds. FR owns any complaint about being suspended, a suspension notice, or reinstatement, even when the citizen calls it a "license problem".'),
    ('TITLE', 'Bureau of Certificate of Title and Anti-Theft', 'Title & Anti-Theft',
     'certificates of title, duplicate or lost titles, title corrections, liens and lien releases, VIN verification and VIN discrepancies, salvage and rebuilt titles, stolen or suspected-stolen vehicles, odometer statements on transfer. TITLE owns ownership paperwork; registering the vehicle once titled is REG.'),
    ('DEALER', 'Dealer Bureau (dealer and inspection station sections)', 'Dealers & Inspections',
     'licensed motor vehicle dealers (dealer plates, temporary plates issued by dealers, dealer conduct and paperwork delays), official inspection stations and inspectors, failed or disputed state inspections, inspection stickers, emissions/OBD testing at stations. A dealer that never delivered a title or registration to a buyer is DEALER, not TITLE or REG.')
ON CONFLICT (id) DO NOTHING;
